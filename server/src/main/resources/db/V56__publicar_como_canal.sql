-- wtfuck V56 - publicar en canal con nombre propio o nombre del canal
--
-- Una publicacion aparecia SIEMPRE con el username de quien la escribio. En un
-- canal eso muchas veces no es lo que se quiere: lo normal es que hable "el
-- canal", no la persona concreta detras. Ahora cada publicacion recuerda con
-- que identidad se hizo, y el historial muestra el nombre del canal o el
-- username segun eso.
--
-- `true` por defecto -hablar como el canal- porque es el caso esperado y porque
-- las publicaciones que ya existen se hicieron pensando en el canal como voz.

ALTER TABLE publicacion_contenido
    ADD COLUMN IF NOT EXISTS como_canal boolean NOT NULL DEFAULT true;
