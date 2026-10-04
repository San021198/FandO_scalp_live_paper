# AI F&O Scalper V2.1 — Live Data / Paper Trading

This package keeps the exact Android project layout and Gradle setup of the supplied working V1 project. The app adds live Angel One market data while keeping all trades virtual.

## Build

Open:
`android/`

Or from CMD:

```cmd
cd ai_scalper_v02_1\android
gradlew.bat assembleDebug
```

APK:
`app\build\outputs\apk\debug\app-debug.apk`

## What it does

- Angel One SmartAPI login using client code + PIN/MPIN + current TOTP
- Downloads Angel One's instrument master
- Finds the nearest future NIFTY index-option expiry and nearest ATM CE/PE pair
- Subscribes to live NIFTY spot + selected CE + selected PE
- Builds the 09:15–09:30 opening range from live NIFTY ticks
- Produces a simple prototype opening-range signal after 09:30
- Lets you enter one virtual trade per day
- Monitors the actual option LTP for virtual SL/target
- Records virtual P&L using the option lot size
- No placeOrder / modifyOrder / cancelOrder API is included

## Important

This is a paper-trading prototype, not a validated profitable AI strategy. The signal is deliberately simple and must be replaced/evaluated with historical and out-of-sample ML before live trading is considered.

Never send your API key, PIN or TOTP to anyone. They are entered locally in the app.

Angel One documents the instrument master at:
https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json
and SmartAPI authentication at the official SmartAPI documentation.
