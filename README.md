# IGM Lector Documentos

Proyecto en Kotlin que combina los ejemplos del repositorio oficial de Regula para lector de documentos y captura de huellas dactilares, brandeado con iconos y colores del Instituto Guatemalteco de Migración (IGM).

- [Overview](#overview)
- [Funciones esenciales](#funciones-esenciales)
- [Configuración de periféricos](#configuración-de-periféricos)
- [Requisitos](#requisitos)
- [Instalación](#instalación)
- [Documentación y recursos](#documentación-y-recursos)
- [Demo Application](#demo-application)
- [Technical Support](#technical-support)
- [Business Enquiries](#business-enquiries)

## Overview

Aplicación que integra el SDK Document Reader de Regula para:

- Lectura de documentos de identidad (pasaportes, DPI, licencias) mediante el dispositivo Regula 1120.
- Captura de huellas dactilares mediante escáner Bluetooth de baja frecuencia.

Este proyecto combina dos ejemplos del repositorio oficial [`DocumentReader-Android`](https://github.com/regulaforensics/DocumentReader-Android/tree/master) — lectura de documentos y captura de huellas — en una sola aplicación brandeada con la identidad visual del IGM (paleta de colores institucionales, logotipo como icono de la app).

## Funciones esenciales

1. **Escaneo de documentos** — Lectura de zona MRZ, extracción de datos del chip RFID, verificación de autenticidad y comparación facial (documento vs cámara en vivo).
2. **Captura de huella dactilar** — Conexión a escáner Bluetooth, captura de huella en vivo y visualización de la imagen capturada.
3. **Exportación de resultados** — Visualización de datos extraídos en formato JSON.

## Configuración de periféricos

### Regula 1120 (lector de documentos)

El dispositivo se conecta por Bluetooth. En la pantalla de conexión (`ConnectDeviceActivity`) se ingresa el nombre del dispositivo.

- **Nombre por defecto:** `Regula 0472`
- Para cambiarlo, editar el `hint` del campo `ed_device` en `activity_connect.xml` o modificar el valor por defecto en `ConnectDeviceActivity.kt`.

### Escáner de huella dactilar

El escáner se conecta por Bluetooth mediante su dirección MAC.

- La dirección MAC se configura directamente en el código de `MainActivity.kt`.
- El botón **"Connect Scanner"** inicia la conexión con la MAC configurada.

### Requisito común

Para detectar tanto el nombre del Regula 1120 como la dirección MAC del escáner de huellas, se necesita un **lector Bluetooth de baja frecuencia** (BLE scanner) que exponga los nombres de dispositivo y direcciones MAC disponibles en el área.

## Requisitos

| Requisito | Versión |
|-----------|---------|
| Android Studio | Última versión estable |
| JDK | 11 o superior |
| API Level | 11 (Android 11 / API 30) o superior |
| Lenguaje | Kotlin |

## Instalación

1. Clonar el repositorio actual.
2. Abrir el proyecto `CombinedDevice-Kotlin` en Android Studio.
3. Copiar el archivo de base de datos `db.dat` desde el [Regula Client Portal](https://client.regulaforensics.com/customer/databases) a la ruta `app/src/main/assets/Regula/`.
4. Ejecutar el proyecto en un dispositivo con API 11+.

**Nota:** Android Gradle plugin requiere Java 11 para ejecutarse.

## Documentación y recursos

- [Documentación oficial del SDK Document Reader](https://docs.regulaforensics.com/develop/doc-reader-sdk/mobile/getting-started/)
- [Recursos del SDK (bases de datos, licencias)](https://docs.regulaforensics.com/resources/doc-reader-sdk/mobile/)
- [Repositorio oficial con ejemplos](https://github.com/regulaforensics/DocumentReader-Android/tree/master)

## Demo Application

<a target="_blank" href="https://play.google.com/store/apps/details?id=com.regula.documentreader">Regula Document Reader Android Demo Application on Google Play</a>

## Technical Support

To submit a request to the Support Team, visit <a target="_blank" href="https://support.regulaforensics.com/hc/en-us/requests/new?utm_source=github">Regula Help Center</a>.

## Business Enquiries

To discuss business opportunities, fill the <a target="_blank" href="https://explore.regula.app/docs-support-request">Enquiry Form</a> and specify your scenarios, applications, and technical requirements.
