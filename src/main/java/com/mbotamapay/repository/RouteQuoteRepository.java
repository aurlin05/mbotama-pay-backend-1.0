package com.mbotamapay.repository;

import com.mbotamapay.entity.RouteQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface RouteQuoteRepository extends JpaRepository<RouteQuote, String> {

    @Modifying
    @Query("DELETE FROM RouteQuote q WHERE q.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
