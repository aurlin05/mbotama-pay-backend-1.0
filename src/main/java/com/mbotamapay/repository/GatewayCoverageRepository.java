package com.mbotamapay.repository;

import com.mbotamapay.entity.GatewayCoverage;
import com.mbotamapay.entity.enums.GatewayType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GatewayCoverageRepository extends JpaRepository<GatewayCoverage, GatewayType> {
}
