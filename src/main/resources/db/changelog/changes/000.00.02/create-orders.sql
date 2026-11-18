CREATE TABLE orders (
    id BIGINT NOT NULL DEFAULT nextval('orders_seq'),
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_price NUMERIC(19, 2) NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT orders_key PRIMARY KEY (id),
    CONSTRAINT chk_orders_user_id CHECK (user_id > 0),
    CONSTRAINT chk_orders_total_price CHECK (total_price >= 0)
);
