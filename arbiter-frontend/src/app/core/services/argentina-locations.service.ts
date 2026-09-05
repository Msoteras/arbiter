import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, shareReplay } from 'rxjs';

/** Localidades agrupadas por provincia: `{ "Buenos Aires": ["Adrogué", ...], ... }`. */
type LocalitiesByProvince = Record<string, string[]>;

/**
 * Catálogo geográfico de Argentina (24 provincias + ~3.900 localidades) para los campos de
 * ubicación del hecho. Sale del dataset oficial de Georef (datos.gob.ar), congelado como asset
 * estático en `public/data/ar-localities.json`.
 *
 * <p>Es un asset y no un endpoint de `rules-service` a propósito: no es una regla de negocio ni
 * varía por aseguradora, y resolverlo en el cliente evita un round trip por cada tecla del buscador.
 * Se descarga una sola vez por sesión (`shareReplay`) — 60 KB, cargados recién cuando alguien abre
 * el wizard.
 *
 * <p>El dataset es la fuente de verdad de lo que puede quedar en `cases.province`/`cases.locality`:
 * el wizard solo deja elegir de acá, así que esas columnas son agrupables sin normalizar prosa.
 */
@Injectable({ providedIn: 'root' })
export class ArgentinaLocationsService {
  private readonly http = inject(HttpClient);

  private readonly data$ = this.http
    // Path absoluto: XHR resuelve los relativos contra la URL de la ruta actual, no contra el
    // <base href>, así que "data/…" fallaba al abrir el wizard desde /denuncias/nueva.
    .get<LocalitiesByProvince>('/data/ar-localities.json')
    .pipe(shareReplay({ bufferSize: 1, refCount: false }));

  /** Nombres de provincia, ya ordenados alfabéticamente en el dataset. */
  provinces(): Observable<string[]> {
    return this.data$.pipe(map((byProvince) => Object.keys(byProvince)));
  }

  /** Localidades de una provincia. Vacío si no se eligió ninguna todavía. */
  localities(province: string): Observable<string[]> {
    if (!province) {
      return of([]);
    }
    return this.data$.pipe(map((byProvince) => byProvince[province] ?? []));
  }
}
