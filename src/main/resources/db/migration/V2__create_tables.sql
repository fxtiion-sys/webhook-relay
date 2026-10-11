CREATE TABLE endpoints (
                           id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                           url text NOT NULL,
                           secret text NOT NULL,
                           event_types text[] NOT NULL,
                           active boolean NOT NULL DEFAULT true,
                           rate_limit_per_sec integer,
                           created_at timestamptz NOT NULL DEFAULT now(),

                           CONSTRAINT chk_endpoints_url_not_blank
                               CHECK (length(trim(url)) > 0),

                           CONSTRAINT chk_endpoints_secret_not_blank
                               CHECK (length(trim(secret)) > 0),

                           CONSTRAINT chk_endpoints_event_types_not_empty
                               CHECK (cardinality(event_types) > 0),

                           CONSTRAINT chk_endpoints_rate_limit_positive
                               CHECK (rate_limit_per_sec > 0)
);

CREATE TABLE events (
                        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                        event_type text NOT NULL,
                        payload jsonb NOT NULL,
                        idempotency_key text,
                        created_at timestamptz NOT NULL DEFAULT now(),

                        CONSTRAINT chk_events_event_type_not_blank
                            CHECK (length(trim(event_type)) > 0),

                        CONSTRAINT chk_events_payload_object
                            CHECK (jsonb_typeof(payload) = 'object'),

                        CONSTRAINT chk_events_idempotency_key_not_blank
                            CHECK (length(trim(idempotency_key)) > 0),

                        CONSTRAINT uq_events_idempotency_key
                            UNIQUE (idempotency_key)
);
CREATE TABLE deliveries (
                            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                            event_id uuid NOT NULL REFERENCES events(id),
                            endpoint_id uuid NOT NULL REFERENCES endpoints(id),
                            status text NOT NULL DEFAULT 'PENDING',
                            attempt_count integer NOT NULL DEFAULT 0,
                            next_attempt_at timestamptz NOT NULL DEFAULT now(),
                            locked_until timestamptz,
                            last_status_code integer,
                            last_error text,
                            created_at timestamptz NOT NULL DEFAULT now(),
                            updated_at timestamptz NOT NULL DEFAULT now(),

                            CONSTRAINT uq_deliveries_event_endpoint
                                UNIQUE (event_id, endpoint_id),

                            CONSTRAINT chk_deliveries_status
                                CHECK (
                                    status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD')
                                    ),

                            CONSTRAINT chk_deliveries_attempt_count
                                CHECK (attempt_count >= 0),

                            CONSTRAINT chk_deliveries_last_status_code
                                CHECK (last_status_code BETWEEN 100 AND 599),

                            CONSTRAINT chk_deliveries_lease
                                CHECK (
                                    (status = 'IN_PROGRESS' AND locked_until IS NOT NULL)
                                        OR
                                    (status <> 'IN_PROGRESS' AND locked_until IS NULL)
                                    )
);
CREATE TABLE delivery_attempts (
                                   id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                   delivery_id uuid NOT NULL REFERENCES deliveries(id),
                                   attempt_no integer NOT NULL,
                                   status_code integer,
                                   error text,
                                   duration_ms bigint NOT NULL,
                                   attempted_at timestamptz NOT NULL,

                                   CONSTRAINT uq_delivery_attempts_delivery_attempt
                                       UNIQUE (delivery_id, attempt_no),

                                   CONSTRAINT chk_delivery_attempts_attempt_no
                                       CHECK (attempt_no > 0),

                                   CONSTRAINT chk_delivery_attempts_status_code
                                       CHECK (status_code BETWEEN 100 AND 599),

                                   CONSTRAINT chk_delivery_attempts_duration
                                       CHECK (duration_ms >= 0)
);
CREATE INDEX idx_deliveries_pending_due
    ON deliveries (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_deliveries_expired_lease
    ON deliveries (locked_until, id)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_deliveries_endpoint_id
    ON deliveries (endpoint_id);