package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import org.springframework.data.jpa.domain.Specification;

public interface CaseLensCountRepository {

    /**
     * Los siete conteos de las lentes en una sola query, con agregación condicional.
     *
     * <p>Eran cinco {@code count(spec)} distintos. Contra Railway cada statement cuesta ~0,8 s de
     * ida y vuelta sin importar cuántas filas toque —medido: el mismo endpoint tarda lo mismo
     * filtrando por algo que no existe— así que el costo del endpoint era la cantidad de viajes,
     * no el trabajo de la base.
     *
     * <p>{@code open}/{@code closed} se suman sobre {@code spec} tal cual llega —SIN el recorte de
     * {@code scope} que ya trae la pestaña activa—, igual que los otros cinco: el número al lado de
     * "En curso" tiene que ser el mismo esté parado en esa pestaña o en "Todos".
     *
     * @param me el analista del request, o null si no tiene perfil en el tenant (el referente)
     */
    LensCounts countLenses(Specification<Case> spec, Long me);

    record LensCounts(long all, long mine, long assigned, long unassigned, long fraud, long open, long closed) {
    }
}
