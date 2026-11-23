package cl.duoc.pedidos360.audit.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    boolean existsByEventId(String eventId);

    /** Timeline de un pedido: sus eventos y los movimientos de stock asociados, en orden. */
    List<AuditEvent> findByPedidoIdOrderByOcurridoEnAscIdAsc(Long pedidoId);

    @Query("""
            select a from AuditEvent a
            where (:entidad is null or a.entidad = :entidad)
              and (:entidadId is null or a.entidadId = :entidadId)
              and (:tipo is null or a.tipo = :tipo)
              and (:usuario is null or lower(a.usuario) = lower(:usuario))
            order by a.ocurridoEn desc, a.id desc""")
    List<AuditEvent> search(@Param("entidad") String entidad, @Param("entidadId") Long entidadId,
                            @Param("tipo") String tipo, @Param("usuario") String usuario, Pageable page);

    @Query("select a.tipo, count(a) from AuditEvent a group by a.tipo order by a.tipo")
    List<Object[]> countByTipo();
}
