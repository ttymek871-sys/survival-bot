# Survival Bot — Fabric 1.21.11

Prosty prototyp moda dla Minecraft Java 1.21.11 + Fabric. Po 10 sekundach od wejścia gracza pojawia się nazwany wieśniak „Survival Bot”. Rozpoznaje polecenia na czacie zaczynające się od `bot` lub `survival bot`.

## Polecenia na czacie
- `bot zbierz drewno` — szuka kłód w pobliżu, podchodzi i je zbiera (prototyp przechowuje je jako licznik).
- `bot wykop kamień` — jeśli ma wystarczająco drewna, „wytwarza” drewniany kilof i zbiera kamień/kamienny bruk w pobliżu.
- `bot zbuduj ścianę` — zużywa do 6 zebranych jednostek kamienia i próbuje zbudować małą ścianę z bruku obok gracza.
- `bot wróć` / `bot przynieś` — wraca do gracza i oddaje materiały.
- `bot stop` — zatrzymuje bieżące zadanie.
- `bot status` — pokazuje stan materiałów.

## Ważne ograniczenia prototypu
To pierwszy, prosty prototyp AI, nie pełny autonomiczny gracz. Bot jest wieśniakiem, niszczenie bloków jest uproszczone (bez prawdziwego czasu kopania, narzędzi i dropów z bloku), a drewno/kamień przechowuje we własnym liczniku. Budowanie jest prostą demonstracją: bot stawia ścianę w stałym miejscu obok gracza, jeśli bloki są wolne i mają podłoże. Wyszukiwanie bloków obejmuje ograniczony obszar wokół bota. Wpisuj polecenia blisko bota. Testuj w nowym świecie lub kopii świata.

## Wymagania
- Java JDK 21
- Minecraft Java 1.21.11
- Fabric Loader i Fabric API dla 1.21.11
- Gradle (lub otwórz projekt w IntelliJ IDEA i użyj Gradle)

## Budowanie pliku JAR
1. Rozpakuj ZIP.
2. Otwórz folder projektu w IntelliJ IDEA jako projekt Gradle.
3. Upewnij się, że używasz JDK 21.
4. Uruchom zadanie Gradle `build`.
5. Plik moda będzie w `build/libs/survivalbot-1.0.0.jar`.
6. Skopiuj ten JAR do folderu `.minecraft/mods`. Zainstaluj Fabric Loader i Fabric API w wersji zgodnej z Minecraft 1.21.11.

## Uwagi
W pierwszym uruchomieniu Gradle pobierze zależności z internetu. Wersje zależności w `gradle.properties` są ustawione dla Minecraft 1.21.11; jeśli Fabric opublikuje nowsze wydania, można je zaktualizować.


## Automatyczne budowanie pliku JAR przez GitHub Actions

Projekt zawiera workflow `.github/workflows/build.yml`, który próbuje skompilować moda po przesłaniu plików do repozytorium GitHub i udostępnia plik JAR jako artefakt.

1. Utwórz nowe repozytorium na GitHubie.
2. Wgraj do niego zawartość tego folderu (wraz z ukrytym folderem `.github`).
3. Otwórz kartę **Actions** i wybierz workflow **Build Survival Bot JAR**. Po wgraniu zmian powinien uruchomić się automatycznie; można też uruchomić go ręcznie.
4. Po zakończeniu otwórz ukończone uruchomienie workflow i pobierz artefakt **SurvivalBot-JAR**.

To jest automatyczna próba kompilacji — nie oznacza, że projekt został już zbudowany lub przetestowany. Jeśli workflow zakończy się błędem, jego log pokaże, co trzeba poprawić.
