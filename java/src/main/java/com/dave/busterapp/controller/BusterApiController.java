package com.dave.busterapp.controller;

import com.dave.busterapp.model.ApiKey;
import com.dave.busterapp.model.BusterTransaction;
import com.dave.busterapp.model.BusterWebhook;
import com.dave.busterapp.model.OkResponse;
import com.dave.busterapp.model.Transaction;
import com.dave.busterapp.model.TransactionStatus;
import com.dave.busterapp.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@Controller
public class BusterApiController {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Logger LOGGER = LoggerFactory.getLogger(BusterApiController.class);

    private final TransactionRepository transactionRepository;
    private final OkHttpClient httpClient;
    private final String busterApiUrl;
    private final ObjectMapper objectMapper;
    private ApiKey apiKey;
    private Map<String, String> authenticationHeader;

    public BusterApiController(final TransactionRepository transactionRepository,
                               final OkHttpClient httpClient,
                               final String busterApiUrl,
                               final ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.httpClient = httpClient;
        this.busterApiUrl = busterApiUrl;
        this.objectMapper = objectMapper;
    }

    /**
     * Post transaction.
     *
     * @return created transaction
     */
    @PostMapping("/transaction")
    @ResponseBody
    public Transaction createTransaction() {
        if (apiKey == null) {
            apiKey = getApiKey();
            authenticationHeader = Collections.singletonMap("X-API-KEY", apiKey.getKey());
        }
        String referenceId = UUID.randomUUID().toString();
        BusterTransaction busterTransaction;
        try {
            busterTransaction = sendPost(String.format("{\"referenceId\": \"%s\"}", referenceId),
                                         authenticationHeader,
                                         String.format("%s/v1/transaction", busterApiUrl),
                                         BusterTransaction.class);
            LOGGER.info("Created transaction remotely with reference id ({}) ", referenceId);
        } catch (ResponseStatusException e) {
            LOGGER.warn("Buster server failure, store the transaction ({}) with UNKNOWN state. Exception detail:", referenceId, e);
            // in case of server error from Buster, we want to temporarily save the transaction with UNKNOWN state
            // as sometimes Buster did create the transaction on their end but timed out before returning the result
            // this happens when Buster sometimes calls our webhooks with a previously failed referenceId
            // for those UNKNOWN, another offline process will check with Buster and notify customer of any failure after N hours
            busterTransaction = new BusterTransaction();
            busterTransaction.setCreated(OffsetDateTime.now());
            busterTransaction.setReferenceId(referenceId);
            busterTransaction.setStatus(TransactionStatus.UNKNOWN.name());
        }
        if (!referenceId.equals(busterTransaction.getReferenceId())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Buster API returned inconsistent referenceId");
        }
        Transaction transaction = new Transaction();
        transaction.setReferenceId(referenceId);
        transaction.setCreated(busterTransaction.getCreated());
        transaction.setExternalId(busterTransaction.getId());
        transaction.setStatus(busterTransaction.getStatus());
        transactionRepository.save(transaction);
        LOGGER.info("Saved transaction locally with reference id ({}) ", referenceId);
        return transaction;
    }

    /**
     * Send post request to remote serer and parse the response into object.
     *
     * @param jsonBody body in json format
     * @param header   request header in map format
     * @param url      server url
     * @param clazz    object class
     * @param <T>      generics
     * @return response object
     */
    private <T> T sendPost(final String jsonBody, final Map<String, String> header, final String url, final Class<T> clazz) {
        Request.Builder requestBuilder = new Request.Builder().url(url);
        if (header != null) {
            header.forEach(requestBuilder::addHeader);
        }
        Request request = requestBuilder.post(okhttp3.RequestBody.create(jsonBody, JSON)).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String reason = response.body() != null ? response.body().string() : response.message();
                throw new ResponseStatusException(HttpStatus.valueOf(response.code()), "Buster API failed with reason: " + reason);
            }
            return objectMapper.readValue(response.body().string(), clazz);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot post the request", e);
        }
    }

    /**
     * Get API Key from Buster.
     *
     * @return API Key
     */
    private ApiKey getApiKey() {
        String webhookUrl = getWebhookUrl();
        LOGGER.info("Got webhook URL ({}) from local ngrok service", webhookUrl);
        ApiKey apiKey = sendPost(String.format("{\"webhookUrl\": \"%s\"}", webhookUrl),
                                 Collections.singletonMap("Content-Type", "application/json"),
                                 String.format("%s/v1/api_key", busterApiUrl),
                                 ApiKey.class);
        LOGGER.info("Got the API key ({}) from Buster", apiKey.getKey());
        return apiKey;
    }

    /**
     * Get webhookUrl from ngrok.
     *
     * @return webhookUrl
     */
    private String getWebhookUrl() {
        Request request = new Request.Builder().url("http://buster-ngrok:4040/api/tunnels").build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String reason = response.body() != null ? response.body().string() : response.message();
                throw new ResponseStatusException(HttpStatus.valueOf(response.code()), "Local Ngrok service failed with reason: " + reason);
            }
            JsonNode rootNode = objectMapper.readTree(response.body().string());
            String publicUrl = rootNode.path("tunnels").get(1).path("public_url").asText();
            return String.format("%s/webhooks", publicUrl);
        } catch (IOException e) {
            LOGGER.error("Caught exception sending get request", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Local Ngrok service failed with exception", e);
        }
    }

    /**
     * Webhook to update transaction from Buster.
     * One observation is Buster is not guaranteed to send webhook for all the transactions created, so
     * we can potentially have an offline process that read any non-final state transaction from DB and
     * query Buster for the latest state of the transaction, this is out of scope of this version.
     *
     * @param busterWebhook webhook data from Buster
     * @return updated transaction
     */
    @PostMapping("/webhooks")
    @ResponseBody
    public Transaction webhooks(@Valid @RequestBody final BusterWebhook busterWebhook) throws InterruptedException {
        BusterTransaction busterTransaction = busterWebhook.getData();
        if (busterTransaction == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction should not be empty");
        }
        String referenceId = busterTransaction.getReferenceId();
        if (referenceId == null || referenceId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reference id should not be empty");
        }
        Transaction transaction = transactionRepository.findByReferenceId(referenceId);
        if (transaction == null) {
            // in case of webhooks come before finishing saving previous transaction in DB
            // we can potentially put them into a Queue which can be later processed asynchronously
            // instead of doing the Queue approach, simply retry after 2 seconds to keep it simple for this version
            Thread.sleep(2000);
            transaction = transactionRepository.findByReferenceId(referenceId);
            if (transaction == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found with this referenceId: " + referenceId);
            }
        }
        if (TransactionStatus.valueOf(transaction.getStatus()).isFinal()) {
            // return the current transaction directly as it's already in final state
            // sometimes Buster API returns pending after complete which are out of order
            // so don't proceed if status is already final
            LOGGER.warn("Transaction with referenceId ({}) is already in final state ({}), "
                                + "this request might come with bad order, skip updating DB.", referenceId, transaction.getStatus());
            return transaction;
        }
        transaction.setStatus(busterTransaction.getStatus());
        // very interesting behavior, created sometimes not consistent between first post and later webhook
        transaction.setCreated(busterTransaction.getCreated());
        transaction.setExternalId(busterTransaction.getId());
        return transactionRepository.save(transaction);
    }

    /**
     * Get all transactions.
     *
     * @return all transactions
     */
    @GetMapping("/transaction")
    @ResponseBody
    public Iterable<Transaction> getAllTransactions() {
        return transactionRepository.findAll();
    }

    /**
     * Simple home page.
     *
     * @return simple ok response
     */
    @GetMapping("/")
    @ResponseBody
    public OkResponse home() {
        OkResponse okResponse = new OkResponse();
        okResponse.setOk(true);
        return okResponse;
    }
}

