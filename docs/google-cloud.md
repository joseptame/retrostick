# Google Cloud: proyecto RetroStick

> **Estado (2026-11-03):** no se pudo crear un proyecto nuevo, así que se usa el proyecto existente del usuario. Ya
> tenía la Google Drive API habilitada y la pantalla de consentimiento en **Prueba**, con el usuario como usuario de
> prueba. "Publicar app" está bloqueado hasta añadir página principal y política de privacidad en *Información de la
> marca*. Mientras siga en Prueba, **Google desconecta el stick cada 7 días** (la cabecera dirá "Drive sin conectar";
> se vuelve a conectar desde Retro Ajustes). Cliente de TV creado y probado: el stick está conectado.
> **Hecho (2026-11-03): app publicada (*En producción*)** con la página principal y la política de privacidad de
> GitHub Pages (`site/`, paso 3b). No se ha enviado a revisión: el aviso "Tu app requiere una verificación" solo
> implica la pantalla de "app no verificada" al conectar y un máximo de 100 usuarios. El stick se reconectó después
> de publicar (los permisos concedidos en modo Prueba caducan a los 7 días aunque se publique luego).
> Solo cambia el estado del proyecto de Google: no se publica en ninguna tienda.

Necesario para que el stick (y más adelante la app del móvil) usen tu Google Drive. Se hace una vez, en el navegador
del PC, con tu cuenta de Gmail. Gratis.

> Los nombres de los menús de la consola de Google cambian a veces; si alguno no coincide, busca el equivalente.

## 1. Crear el proyecto

1. Entra en <https://console.cloud.google.com>.
2. Arriba, selector de proyectos → **Proyecto nuevo** → nombre **RetroStick** → **Crear**. Asegúrate de que queda
   seleccionado.

## 2. Activar la API de Google Drive

1. Menú ☰ → **APIs y servicios** → **Biblioteca**.
2. Busca **Google Drive API** → **Habilitar**.

## 3. Pantalla de consentimiento (Google Auth Platform)

1. Menú ☰ → **APIs y servicios** → **Pantalla de consentimiento de OAuth** (o **Google Auth Platform**) → **Empezar**.
2. **Nombre de la app:** `RetroStick`. **Correo de asistencia:** tu Gmail.
3. **Público:** **Externo**.
4. **Información de contacto:** tu Gmail. Acepta la política y **Crear**.
5. **Publicar la app:** en **Público**, pulsa **Publicar app** para pasarla a **En producción**.
   - Motivo: en modo *Prueba*, Google caduca los permisos cada **7 días** y el stick tendría que volver a conectarse cada
     semana.
   - RetroStick solo pide el permiso `drive.file` (ver y modificar **solo los archivos que crea la propia app**), que
     Google clasifica como no sensible: no hace falta revisión de Google. ⚠️ VERIFICAR al publicar que la consola no
     pide verificación.
   - Al conectar, Google puede mostrar un aviso porque la app no está verificada: es tu propia app; acéptalo.

## 3b. Información de la marca (necesaria para publicar)

En **Google Auth Platform → Información de la marca**:

- **Página principal de la aplicación:** `https://joseptame.github.io/retrostick/`
- **Política de privacidad:** `https://joseptame.github.io/retrostick/privacidad.html`
- **Dominios autorizados:** `joseptame.github.io`
- Guardar. Después, **Público → Publicar app**.

Las páginas están en `site/` y las publica `.github/workflows/pages.yml` en GitHub Pages. El contacto es el repositorio
de GitHub (no se publica ningún correo).

## 4. Cliente para el stick

1. **Google Auth Platform** → **Clientes** → **Crear cliente**.
2. **Tipo de aplicación:** **TV y dispositivos de entrada limitada**.
3. **Nombre:** `RetroStick TV` → **Crear**.
4. Copia el **ID de cliente** (termina en `.apps.googleusercontent.com`) y el **Secreto del cliente**.

Pásaselos a Claude para el stick. Se guardan en `android/local.properties`, que **no se sube** al repositorio:

```properties
retrostick.clientId=xxxxxxxx.apps.googleusercontent.com
retrostick.clientSecret=GOCSPX-xxxxxxxx
```

Google indica que en este tipo de cliente el secreto no es realmente secreto (va dentro de la app instalada); aun así no
se publica.

## 5. Conectar el stick

En la TV: pestaña **Retro Ajustes → Sincronizar ahora**. El stick muestra un código. En el móvil, abre
<https://www.google.com/device>, escribe el código y acepta con tu cuenta.

## Qué ve la app en tu Drive

Solo la carpeta `Retro/` y lo que hay dentro, creado por RetroStick (el stick o la app del móvil). Los archivos que
subas a mano desde la web de Drive **no** los ve: los juegos se añaden desde la app del móvil (fase 5) o copiándolos al
stick, que los sube.

## Para la app del móvil (fase 5)

⚠️ VERIFICAR: con `drive.file`, si un archivo creado por un cliente (p. ej. el del stick) es visible para otro cliente
del mismo proyecto. Si no lo es, la app del móvil usará el mismo cliente de TV (inicio de sesión con código).
