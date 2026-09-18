CREATE TABLE vehicles (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    plate      VARCHAR NOT NULL,
    brand      VARCHAR NOT NULL,
    model      VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vehicles_plate UNIQUE (plate),
    CONSTRAINT fk_vehicles_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_vehicles_user_id ON vehicles (user_id);

CREATE TABLE zones (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR NOT NULL,
    city       VARCHAR NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tariffs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    zone_id     BIGINT NOT NULL,
    rule_type   VARCHAR NOT NULL,
    hourly_rate NUMERIC(10,2) NOT NULL,
    currency    CHAR(3) NOT NULL DEFAULT 'EUR',
    valid_from  TIMESTAMPTZ NOT NULL,
    valid_to    TIMESTAMPTZ NULL,
    CONSTRAINT fk_tariffs_zone FOREIGN KEY (zone_id) REFERENCES zones (id)
);

CREATE UNIQUE INDEX uq_tariffs_zone_current ON tariffs (zone_id) WHERE valid_to IS NULL;
