// Paths relativos: en dev los rutea proxy.conf.json al puerto de cada módulo;
// en prod los rutea Nginx. El frontend nunca hardcodea host/puerto.
export const environment = {
  apiBaseUrl: '/api/v1',
};
