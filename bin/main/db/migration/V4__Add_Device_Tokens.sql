-- V4: Add Device Tokens table
-- Migration for push notification device tokens

CREATE TABLE device_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token VARCHAR(500) NOT NULL UNIQUE,
    device_type VARCHAR(20),
    device_name VARCHAR(100),
    is_active BOOLEAN DEFAULT true,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_device_token_user ON device_tokens(user_id);
CREATE INDEX idx_device_token_token ON device_tokens(token);
CREATE INDEX idx_device_token_active ON device_tokens(user_id, is_active);

-- Comment
COMMENT ON TABLE device_tokens IS 'FCM/APNs device tokens for push notifications';
