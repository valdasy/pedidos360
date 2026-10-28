package cl.duoc.pedidos360.mqadmin.domain;

import java.util.Arrays;

/** Los tres flujos de trabajo: cada uno tiene su cola principal y su DLQ. */
public enum Flow {

    EMAIL, KITCHEN, INVOICE;

    public String id() {
        return name().toLowerCase();
    }

    public String queue() {
        return "q.cmd." + id();
    }

    public String dlq() {
        return queue() + ".dlq";
    }

    /** Acepta "email", "EMAIL", etc. */
    public static Flow from(String value) {
        return Arrays.stream(values())
                .filter(f -> f.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Flujo desconocido: " + value + ". Válidos: email, kitchen, invoice"));
    }
}
