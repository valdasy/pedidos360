package cl.duoc.pedidos360.report.reporte;

import org.springframework.data.jpa.repository.JpaRepository;

interface RepPedidoRepository extends JpaRepository<RepPedido, Long> {
}

interface EventoProcesadoRepository extends JpaRepository<EventoProcesado, String> {
}
