# ColorWar (Paper, Java)

Minijuego tipo Paper.io en Minecraft: sal de tu territorio dejando estela de polvo de concreto,
vuelve para cerrar el bucle y conquistar todo lo encerrado. Si pisan tu estela, mueres.

## Compilar
    mvn clean package        # el .jar queda en target/ColorWar-1.0.0.jar

En pom.xml cambia `paper.version` y `java.version` a los de tu servidor
(1.21.x -> Java 21; 26.x -> Java 25). Copia el jar a /plugins.

## Crear una arena
1. Construye un suelo plano de entre 10x10 y 200x200 (con paredes).
2. `/cw wand` te da un hacha de madera (o usa cualquiera).
3. Clic izquierdo en una esquina del suelo = pos1, clic derecho en la esquina opuesta = pos2.
   Los dos puntos deben estar a la misma altura. `/cw sel` muestra el estado de la selección.
4. `/cw create [nombre] [solo|team]` crea la arena si el área es válida y construye el suelo (base-block)
   y un borde visible alrededor (border-block / border-height en config.yml).
5. Opcional: `/cw setlobby [arena]`, `/cw setmainlobby`, `/cw addspawn [arena]` y
   `/cw set [arena] spawnmode defined`, `/cw set [arena] min|max|time|teams [n]`.
6. `/cw admin` abre el menú GUI. `/cw resize [arena]` cambia el área con una nueva selección.

## Jugadores
/cw (menú), /cw join <arena>, /cw leave, /cw list, /cw top, /cw stats

## Permisos
colorwar.play (todos) · colorwar.admin (op)

Recompensas y ajustes: config.yml. Datos: arenas.yml y stats.yml.

## Reglas de la partida
- Al morir pasas unos segundos en espectador (death-spectate-seconds) y sales de la partida.
- Si un jugador completa el mapa (win-percent) o es el último en pie, gana al instante:
  title, partículas y fuegos artificiales a su alrededor; luego la arena queda libre para otra partida.
