-- V1__Initial_Schema.sql
-- MbotamaPay Database Schema

-- Users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    country_code VARCHAR(5) NOT NULL DEFAULT '+221',
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),
    kyc_level VARCHAR(20) NOT NULL DEFAULT 'NONE',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    phone_verified BOOLEAN DEFAULT FALSE,
    email_verified BOOLEAN DEFAULT FALSE,
    profile_picture_url VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    last_login_at TIMESTAMP
);

-- OTP codes table
CREATE TABLE otp_codes (
    id BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL,
    code VARCHAR(6) NOT NULL,
    attempts INTEGER DEFAULT 0,
    verified BOOLEAN DEFAULT FALSE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- KYC documents table
CREATE TABLE kyc_documents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_type VARCHAR(50) NOT NULL,
    document_url VARCHAR(500) NOT NULL,
    verification_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    rejection_reason VARCHAR(500),
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at TIMESTAMP,
    verified_by BIGINT REFERENCES users(id)
);

-- Transactions table (for future use)
CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    sender_id BIGINT REFERENCES users(id),
    sender_phone VARCHAR(20) NOT NULL,
    sender_name VARCHAR(200),
    recipient_phone VARCHAR(20) NOT NULL,
    recipient_name VARCHAR(200),
    amount BIGINT NOT NULL,
    fee BIGINT DEFAULT 0,
    currency VARCHAR(5) DEFAULT 'XOF',
    platform VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    description VARCHAR(255),
    external_reference VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- Indexes
CREATE INDEX idx_users_phone ON users(phone_number);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_kyc_level ON users(kyc_level);
CREATE INDEX idx_otp_phone ON otp_codes(phone_number);
CREATE INDEX idx_otp_expires ON otp_codes(expires_at);
CREATE INDEX idx_kyc_user ON kyc_documents(user_id);
CREATE INDEX idx_transactions_sender ON transactions(sender_id);
CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_created ON transactions(created_at);
