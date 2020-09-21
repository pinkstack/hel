# Project Hel

> *Hel* features in Norse legend as the goddess of the underworld. She was sent by Odin to Helheim/Niflheim to 
> preside over the spirits of the dead, except for those who were killed in battle and went to Valhalla. 
> It was her job to determine the fate of the souls who entered her realm.

## Environment variables

- <pre>RADAR_TOKEN</pre> Access Token for Radar Webservice.

## Data sources

### PromInfo

Resources
- https://prominfo.projekti.si/web/

Trenutno stanje
- [ ] Popolne zapore cest
  - `GET https://prominfo.projekti.si/web/api/MapService/Query/lay_prometnezaporemol/query?`
  - `GET https://prominfo.projekti.si/web/api/MapService/Query/lay_prometnidogodkizapore/query?`
  - `GET https://prominfo.projekti.si/web/api/MapService/Query/lay_popolneZaporeMolPoligoni/query?`
- [ ] Delne zapore cest
- [ ] Gostota prometa
- [ ] Izredni dogodki

Kolesarjenje
- [ ] BicikeLJ
- [ ] Števci kolesarjev

Motorni promet
- [ ] Garažne hiše
- [ ] Parkirišča
- [ ] Parkiraj in prestopi
- [ ] Elektro polnilnice
- [ ] Car sharing
- [ ] Števci prometa

### Radar
Resources
- https://www.amzs.si/aplikacija

- [ ] Active events
  - `GET /mobile/api/v1/events/active`
  

### SPIN3
Resources
- https://spin3.sos112.si/javno/zemljevid

- [ ] Active events
  - `GET /javno/assets/data/lokacija.json`

# Authors

- [Oto Brglez](https://github.com/otobrglez)
