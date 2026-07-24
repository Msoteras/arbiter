// Tono semántico de estado (semáforo). Mapea 1:1 a los tokens --status-* de
// _semantic.scss. 'neutral' = sin acento (gris por defecto del sistema).
// Los componentes del kit reciben un StatusTone y lo traducen al token visual;
// las funciones de dominio (estadoTone, clasificacionTone) lo derivan del enum.
export type StatusTone = 'neutral' | 'ok' | 'warning' | 'danger' | 'info';
