package cl.duoc.pedidos360.mqadmin.domain;

import java.util.List;

/** Respuestas de la API de administración. */
public final class Dtos {

    private Dtos() {
    }

    /** Contadores de una cola. ready + unacked = total. */
    public record QueueStatus(String name, long ready, long unacked, int consumers,
                              double publishRate, double deliverRate) {
    }

    public record FlowStatus(String flow, QueueStatus main, QueueStatus dlq) {
    }

    /** Un mensaje leído de la DLQ (sin retirarlo). */
    public record DlqMessage(String messageId, String type, String reason,
                             String originalExchange, String originalRoutingKey,
                             long deathCount, int reprocessCount, String payload) {
    }

    public record ReprocessResult(String flow, int moved, List<String> skipped) {
    }

    public enum Level { WARNING, CRITICAL }

    public record Alert(Level level, String queue, String message) {
    }

    public record NodeStatus(String name, boolean running, long memUsedMb, long diskFreeMb) {
    }

    public record Overview(String rabbitmqVersion, String clusterName, long totalMessages,
                           int totalConsumers, List<NodeStatus> nodes) {
    }
}
