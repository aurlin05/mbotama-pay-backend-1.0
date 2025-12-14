-- V2: Add Audit Logs table
-- Migration for audit logging system

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(50),
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50),
    entity_id BIGINT,
    old_value TEXT,
    new_value TEXT,
    description VARCHAR(500),
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    request_uri VARCHAR(255),
    request_method VARCHAR(10),
    success BOOLEAN,
    error_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for efficient querying
CREATE INDEX idx_audit_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_action ON audit_logs(action);
CREATE INDEX idx_audit_created_at ON audit_logs(created_at);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);

-- Comment on table
COMMENT ON TABLE audit_logs IS 'Audit trail for all sensitive operations in the system';
