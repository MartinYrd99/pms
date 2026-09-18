CREATE TABLE parking_sessions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    zone_id    BIGINT NOT NULL,
    tariff_id  BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at   TIMESTAMPTZ NULL,
    amount     NUMERIC(10,2) NULL,
    paid_at    TIMESTAMPTZ NULL,
    CONSTRAINT fk_parking_sessions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_parking_sessions_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id),
    CONSTRAINT fk_parking_sessions_zone FOREIGN KEY (zone_id) REFERENCES zones (id),
    CONSTRAINT fk_parking_sessions_tariff FOREIGN KEY (tariff_id) REFERENCES tariffs (id),
    CONSTRAINT ck_parking_sessions_ended_amount CHECK ((ended_at IS NULL) = (amount IS NULL)),
    CONSTRAINT ck_parking_sessions_ended_after_started CHECK (ended_at >= started_at),
    CONSTRAINT ck_parking_sessions_paid_requires_ended CHECK (paid_at IS NULL OR ended_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_parking_sessions_vehicle_unsettled ON parking_sessions (vehicle_id) WHERE paid_at IS NULL;
CREATE INDEX idx_parking_sessions_user_active ON parking_sessions (user_id) WHERE ended_at IS NULL;
CREATE INDEX idx_parking_sessions_user_started_at ON parking_sessions (user_id, started_at DESC);

CREATE TABLE payments (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL,
    amount     NUMERIC(10,2) NOT NULL,
    status     VARCHAR NOT NULL,
    attempts   INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_payments_session FOREIGN KEY (session_id) REFERENCES parking_sessions (id)
);

CREATE UNIQUE INDEX uq_payments_session_live ON payments (session_id) WHERE status IN ('PENDING', 'COMPLETED');
CREATE INDEX idx_payments_pending_created_at ON payments (created_at) WHERE status = 'PENDING';
