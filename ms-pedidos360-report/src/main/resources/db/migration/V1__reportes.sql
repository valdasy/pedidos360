-- Reportes: "proyección" de los pedidos construida SOLO con eventos de Kafka.
-- ms-report no consulta la base de ms-orders (database per service).

CREATE TABLE rep_pedido (
    pedido_id       NUMBER(19)     PRIMARY KEY,
    estado          VARCHAR2(20)   NOT NULL,
    total           NUMBER(19)     NOT NULL,
    cliente_email   VARCHAR2(200),
    creado_en       TIMESTAMP      NOT NULL,
    aceptado_en     TIMESTAMP,
    entregado_en    TIMESTAMP,
    cancelado_en    TIMESTAMP,
    actualizado_en  TIMESTAMP      NOT NULL
);

CREATE TABLE rep_pedido_item (
    pedido_id        NUMBER(19)    NOT NULL REFERENCES rep_pedido (pedido_id),
    producto_id      NUMBER(19)    NOT NULL,
    nombre           VARCHAR2(200) NOT NULL,
    cantidad         NUMBER(10)    NOT NULL,
    precio_unitario  NUMBER(19)    NOT NULL,
    CONSTRAINT pk_rep_pedido_item PRIMARY KEY (pedido_id, producto_id)
);

-- Idempotencia: se inserta en la MISMA transacción que la actualización de rep_pedido.
-- Si el evento llega dos veces, la segunda vez ya está aquí y no se suma de nuevo.
CREATE TABLE rep_evento_procesado (
    event_id      VARCHAR2(36)  PRIMARY KEY,
    tipo          VARCHAR2(50)  NOT NULL,
    procesado_en  TIMESTAMP     NOT NULL
);

CREATE INDEX ix_rep_pedido_estado ON rep_pedido (estado);
CREATE INDEX ix_rep_pedido_creado ON rep_pedido (creado_en);
