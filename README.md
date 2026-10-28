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

## Modo debug (probar en solitario)
`/cw debug [arena] [bots o nombres]` inicia una partida de prueba al instante, sin cuenta atrás, con bots
(por defecto 2, configurable en `debug.default-bots`). Ejemplos: `/cw debug mapa1 3 2`, `/cw debug mapa1 Pepe,Luis` o `/cw debug mapa1 Pepe:1,Luis:3` (nombre:nivel).
Los bots son visibles (modelo de jugador con armadura del color de su equipo y su nombre encima). No guarda estadísticas ni da premios.
Los bots salen, dejan estela, cierran bucles y reaparecen al morir, así puedes probar muertes y capturas.

Dentro de la partida de prueba:
- `/cw debug addbot [nombre] [nivel] [equipo]` añade un bot con nombre y nivel (equipo solo en modo team)
- `/cw debug addbots [n] [nivel]` añade varios bots; `/cw debug setlevel [nombre|todos] [nivel]` cambia su nivel; `/cw debug removebot [nombre]` quita uno
- `/cw debug win` completa el mapa (prueba victoria, title y fuegos artificiales)
- `/cw debug fill [%]` rellena el mapa hasta ese porcentaje
- `/cw debug die` te mata (prueba espectador y salida)
- `/cw debug time [segundos]` fija el tiempo restante (prueba el final por tiempo)
- `/cw debug info` muestra el estado; `/cw debug stop` detiene la prueba
También hay un botón "Probar en solitario" en `/cw admin` → arena.

### Niveles de los bots (1-3, se ven como estrellas junto al nombre)
- **1 Fácil ★**: deambula en línea recta, va a media velocidad, no huye ni ataca.
- **2 Normal ★★**: hace bucles rectangulares para conquistar, vuelve a casa si hay un enemigo cerca
  y ataca estelas enemigas cercanas mientras la suya es corta.
- **3 Difícil ★★★**: bucles más grandes hacia donde hay más terreno (prefiere el enemigo), detecta
  amenazas desde más lejos y persigue estelas enemigas a mayor distancia.
Nivel por defecto: `debug.default-level` en config.yml.
