# Imagen del servidor.
#
# Dos etapas: una compila y la otra solo corre. Lo que llega a produccion no
# lleva Gradle, ni el codigo fuente, ni el cache de dependencias — unos 700 MB
# de cosas que solo sirven para construir y que en un servidor son superficie
# de ataque sin contrapartida.

# ---------------------------------------------------------------- construir
FROM eclipse-temurin:21-jdk AS construir
WORKDIR /obra

# El wrapper y los descriptores PRIMERO, y las fuentes despues.
#
# No es manía: Docker cachea por capa, y asi un cambio en un .kt no vuelve a
# bajar todo el arbol de dependencias. Con todo copiado de una vez, cada
# compilacion se baja internet entera.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY protocol/build.gradle.kts ./protocol/
COPY server/build.gradle.kts ./server/
COPY app/build.gradle.kts ./app/
RUN chmod +x gradlew && ./gradlew --no-daemon :server:dependencies > /dev/null 2>&1 || true

COPY protocol/src ./protocol/src
COPY server/src ./server/src

# `installDist` y no `jar`: deja un lanzador con el classpath ya resuelto, que
# es lo mismo que se usa en desarrollo. Y OJO — este paso es el que copia los
# recursos, o sea las migraciones .sql y la consola web. Construir el jar a
# mano sin ellos da un servidor que arranca y no tiene esquema.
#
# `-x test` porque las pruebas del servidor necesitan una base levantada; se
# corren en el pipeline, no dentro de la imagen.
RUN ./gradlew --no-daemon :server:installDist -x test

# ------------------------------------------------------------------ correr
FROM eclipse-temurin:21-jre
WORKDIR /app

# Usuario propio, sin shell.
#
# Por defecto un contenedor corre como root, y si alguien consigue ejecucion
# dentro del proceso eso le regala root dentro del contenedor — el primer
# escalon para salirse. No cuesta nada y quita un escalon entero.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin wtfuck

COPY --from=construir /obra/server/build/install/server /app

# Sin hijos: el lanzador de Gradle ejecuta `exec java`, asi que el JVM ES el
# PID 1 y recibe el SIGTERM de `docker stop` directamente. Sin eso, parar el
# contenedor mata el shell y deja al JVM esperando los diez segundos del
# timeout en cada despliegue.
USER wtfuck
EXPOSE 8300

# La memoria se toma del contenedor, no de la maquina.
#
# Sin esto, un JVM en un contenedor con limite de 1 GB puede creer que tiene
# toda la RAM del anfitrion y pedir un heap que el cgroup no le va a dar: el
# proceso muere por OOM del kernel, sin excepcion, sin log y sin pista.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# La sonda usa curl y no `/dev/tcp`.
#
# La primera version usaba `exec 3<>/dev/tcp/...`, que es una extension de
# BASH: el `/bin/sh` de esta imagen es dash y no la tiene, asi que la sonda
# fallaba con codigo 2 en CADA intento y el contenedor se quedaba "starting"
# para siempre. Un `depends_on: service_healthy` contra eso no arranca nunca.
#
# Se vio corriendo la imagen y mirando `docker inspect`; leyendo el Dockerfile
# parecia correcta.
#
# El puerto sale de la variable: fijarlo a 8300 hacia que la sonda mirara otro
# sitio en cuanto alguien cambiara WTFUCK_PUERTO.
HEALTHCHECK --interval=15s --timeout=4s --start-period=40s --retries=4     CMD curl -fsS "http://127.0.0.1:${WTFUCK_PUERTO:-8300}/salud" || exit 1

ENTRYPOINT ["/app/bin/server"]
