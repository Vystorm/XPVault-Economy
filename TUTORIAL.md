# XPVaultEconomy – Anleitung

Öffne `/vmenu`, wähle **Tutorials** und danach **XPVaultEconomy · Anleitung**. Direkt geht es mit `/vmenu tutorials`; der direkte Schlüssel `/vmenu xpvaulteconomy:tutorial` bleibt ebenfalls verfügbar. Für den Eintrag brauchst du `xpeconomy.balance`.

## XP als Guthaben

XPVaultEconomy macht echte Minecraft-XP-Punkte zum Vault-Guthaben: 1 Guthaben entspricht 1 XP-Punkt. Online-Guthaben wird jede Sekunde mit der Erfahrungsanzeige abgeglichen und dauerhaft gespeichert. Sammle oder verliere XP wie gewohnt. Im Vystorm-Menü öffnet **Wirtschaft → XP-Konto** (auch `/economy`) einen Dialog mit Kontostand, Zeitbonus und Überweisung. `/money`, `/bal` oder `/economy balance` zeigt dein Guthaben. Beim ersten Betreten werden vorhandene echte XP ins Konto übernommen. Das Standardlimit beträgt 2.000.000.000 XP-Punkte; Währungsname und Limit sind konfigurierbar.

## Eigenes und fremdes Guthaben

Nutze `/money` oder `/bal`, alternativ `/economy balance` oder `/economy guthaben`. Ein bekanntes Spielerkonto fragst du mit `/money Spieler` oder `/economy balance Spieler` ab. Dafür brauchst du `xpeconomy.balance.others`; ohne dieses Recht siehst du nur dein eigenes Guthaben.

## XP an Spieler zahlen — `xpeconomy.pay`

Zahlungen übertragen echte XP-Punkte sofort zwischen bekannten Spielerkonten. Im XP-Konto führt **Überweisen** über Spieler, Betrag und eine Bestätigung. Per Befehl: `/pay Spieler XP-Punkte`, zum Beispiel `/pay Alex 250`. Alternativ: `/economy pay Spieler XP-Punkte` oder `/economy zahlen Spieler XP-Punkte`. Der Betrag muss eine positive ganze Zahl sein. Selbstzahlungen sind nicht möglich. Das Ziel muss bereits ein bekanntes Konto haben; bei einem Fehler bleibt keine Teilzahlung bestehen.

## Online-Zeitbonus

Der Standardbonus zahlt nach jeder vollen Online-Minute XP aus. Die erste Auszahlung beträgt 2 XP; jede abgeschlossene Minute erhöht die nächste Rate um 2 %. Bleibe 60 Sekunden online; standardmäßig erscheint die Auszahlung in der Actionbar. `/money` zeigt Serie, nächste Rate und verbleibende Zeit. Bei Rückkehr innerhalb von 30 Minuten bleiben Serie, Teilminute und Nachkommarest erhalten. Werte sind konfigurierbar; der Bonus kann deaktiviert sein.

## XP-Rangliste — `xpeconomy.top`

`/baltop` (auch `/moneytop`, `/xptop` oder `/economy top`) zeigt die Konten mit den meisten XP-Punkten: Platz, Name, Guthaben und Level. Mit `/baltop 2` oder den Knöpfen **[Weiter »]/[« Zurück]** blätterst du; stehst du nicht auf der Seite, zeigt **Dein Platz** deine Position. Im XP-Konto öffnet **Rangliste** dieselbe Liste als Dialog. Online-Spieler zählen mit ihren aktuellen XP. Die Liste wird kurz zwischengespeichert (Standard 30 Sekunden) und kann daher minimal hinterherhinken.

## Wirtschaftsverwaltung — `xpeconomy.admin`

Administratoren verwalten bekannte Konten mit `/economy admin`. `/economy admin info` zeigt Kontenzahl und Speicher. Startguthaben, Währung, Zeitbonus und Rangliste stellst du unter `/einstellungen` → Server → **XP-Wirtschaft** ein; `/economy admin reload` lädt `config.yml` neu. `/economy admin give Spieler XP-Punkte` schreibt XP gut, `take` zieht XP ab und `set` setzt das Guthaben. Beträge sind nichtnegative ganze Zahlen und dürfen das Kontolimit nicht überschreiten.
