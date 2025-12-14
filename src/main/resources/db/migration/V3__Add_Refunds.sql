-- V3: Add Refunds table
-- Migration for refund management system

CREATE TABLE refunds (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id),
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(500),
    external_reference VARCHAR(100) UNIQUE,
    gateway_response TEXT,
    requested_by BIGINT,
    processed_by BIGINT,
    processed_at TIMESTAMP,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_refund_transaction ON refunds(transaction_id);
CREATE INDEX idx_refund_status ON refunds(status);
CREATE INDEX idx_refund_created_at ON refunds(created_at);

-- Constraint: one refund per transaction
CREATE UNIQUE INDEX idx_refund_unique_transaction ON refunds(transaction_id);

-- Comment
COMMENT ON TABLE refunds IS 'Refund requests and processing status for transactions';
