package cl.duoc.pedidos360.catalog.stock;

import lombok.Getter;

import java.util.List;

@Getter
public class InsufficientStockException extends RuntimeException {

    private final List<StockService.InsufficientItem> items;

    public InsufficientStockException(List<StockService.InsufficientItem> items) {
        super("Stock insuficiente para " + items.size() + " producto(s)");
        this.items = items;
    }
}
