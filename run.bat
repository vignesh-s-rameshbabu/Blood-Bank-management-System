@echo off
chcp 65001 > nul
echo [LifeFlow] Starting web app...
echo Open http://localhost:8080 in your browser
mvn compile exec:java -Dexec.args="web"
