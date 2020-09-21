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
    - `GET /web/api/MapService/Query/lay_prometnezaporemol/query?`
    - `GET /web/api/MapService/Query/lay_prometnidogodkizapore/query?`
    - `GET /web/api/MapService/Query/lay_popolneZaporeMolPoligoni/query?`
- [ ] Delne zapore cest
    - `GET /web/api/MapService/Query/lay_ostaleZaporeMol/query?`
    - `GET /web/api/MapService/Query/lay_prometnidogodkidogodki/query?`
    - `GET /web/api/MapService/Query/lay_delneZaporeMolPoligoni/query?`
- [ ] Izredni dogodki
    - `GET /web/api/MapService/Query/lay_prometnidogodkiizredni/query?`

Kolesarjenje
- [ ] BicikeLJ
    - `GET '/web/api/MapService/Query/lay_bicikelj/query?`

Motorni promet
- [ ] Garažne hiše
    - `GET '/web/api/MapService/Query/lay_vParkiriscagaraznehise/query?`

- [ ] Parkirišča
    - `GET '/web/api/MapService/Query/lay_vParkirisca/query?`

- [ ] Parkiraj in prestopi
    - `GET '/web/api/MapService/Query/lay_vParkiriscapr/query?`

- [ ] Elektro polnilnice
    - `GET '/web/api/MapService/Query/lay_vPolnilnePostaje2/query?`
    - `GET '/web/api/MapService/Query/lay_polnilnepostaje_staticne/query?`

- [ ] Car sharing
    - `GET '/web/api/MapService/Query/lay_vCarsharing/query?`

- [ ] Števci prometa
    - `GET '/web/api/MapService/Query/lay_drsc_stevna_mesta/query?`
    - `GET '/web/api/MapService/Query/lay_MikrobitStevnaMesta/query?`
    - `GET '/web/api/MapService/find?searchText=[ID]&f=json`*

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


### Marprom WEBMap
Resources
- https://www.marprom.si/webmap/website/webmap.php

### 24ur.com / Ceste
Resources
- https://www.24ur.com/novice/ceste

# Authors

- [Oto Brglez](https://github.com/otobrglez)
