import * as request from 'superagent';

const BUSTER_API_URL = process.env.BUSTER_API_URL;

export function createApiKey(webhookUrl: string) {
  return request
    .post(`${BUSTER_API_URL}/v1/api_key`)
    .send({ webhookUrl })
    .then(res => res.body);
}

export function createTransaction(referenceId: string) {
  return request
    .post(`${BUSTER_API_URL}/v1/transaction`)
    .send({ referenceId })
    .then(res => res.body);
}

export function getTransaction(externalId: string) {
  return request
    .get(`${BUSTER_API_URL}/v1/transaction/${externalId}`)
    .then(res => res.body);
}

export function getTransactionByReferenceId(referenceId: string) {
  return request
    .get(`${BUSTER_API_URL}/v1/transaction`)
    .query({ referenceId })
    .then(res => res.body);
}
