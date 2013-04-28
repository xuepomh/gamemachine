@echo off

set AKKA_HOME=%~dp0..
set JAVA_OPTS=-Xmx1024M -Xms1024M -Xss1M -XX:MaxPermSize=256M -XX:+UseParallelGC
set AKKA_CLASSPATH=%AKKA_HOME%\java_lib\scala-library.jar;%AKKA_HOME%\config;%AKKA_HOME%\java_lib\*

java %JAVA_OPTS% -cp "%AKKA_CLASSPATH%" -Dakka.home="%AKKA_HOME%" com.game_machine.GameMachine %*