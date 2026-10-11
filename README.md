# Mis Finanzas (SMS)

App Android que registra cada transacción bancaria leyendo los SMS del banco: valor, cuenta y establecimiento. Todo se guarda solo en el teléfono.

## Cómo instalarla en tu celular
1. Instala [Android Studio](https://developer.android.com/studio) y abre esta carpeta (`finanzas-sms`).
2. Espera a que termine el "Gradle sync".
3. En el celular: Ajustes → Opciones de desarrollador → activa "Depuración USB" y conéctalo al computador.
4. Pulsa **Run ▶** en Android Studio. La app queda instalada.
5. Al abrirla, acepta el permiso de SMS. Pulsa **Importar SMS anteriores** para cargar el historial.

## Alternativa sin Android Studio: compilar el APK en la nube (GitHub, gratis)
1. Crea una cuenta en github.com y un repositorio nuevo.
2. Sube el contenido de esta carpeta (incluida la carpeta oculta `.github`).
3. Entra a la pestaña **Actions** → **Compilar APK** → **Run workflow**.
4. Cuando termine (unos minutos), abre la ejecución y descarga el archivo `mis-finanzas-apk`: dentro está `app-debug.apk`.
5. Pásalo al celular e instálalo (el teléfono te pedirá permitir "instalar apps de orígenes desconocidos").

## Cómo funciona
- `SmsReceiver.kt`: se activa cuando llega un SMS y lo guarda si es una transacción.
- `SmsParser.kt`: interpreta el texto del banco (valor, últimos 4 dígitos de la cuenta, establecimiento). Ignora códigos OTP, compras rechazadas, consultas de saldo y SMS personales.
- `TransactionStore.kt`: base de datos local (SQLite).
- `MainActivity.kt`: compras e ingresos, resumen de gastos del mes por cuenta, registro manual y borrar (mantén pulsado un registro).
- `TransfersActivity.kt`: módulo **Transferencias** (botón en la pantalla principal). Muestra lo que envías y lo que te envían, con monto, tu banco y cuenta, y el banco/cuenta/nombre de la otra parte. Toca un registro para ver el SMS original. Las transferencias no cuentan como gasto.

## Ajustar a tus bancos
Cada banco redacta distinto sus SMS. Si alguna transacción sale como "Sin identificar" o no se registra, copia el SMS (sin datos sensibles) y ajusta `SmsParser.kt`. Los bancos reconocidos están en la lista `BANKS`.
