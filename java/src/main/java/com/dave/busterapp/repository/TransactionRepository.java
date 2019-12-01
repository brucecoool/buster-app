package com.dave.busterapp.repository;

import com.dave.busterapp.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransactionRepository extends JpaRepository<Transaction, Integer> {
    @Query("SELECT t FROM Transaction t WHERE t.referenceId = ?1")
    Transaction findByReferenceId(String referenceId);

}
