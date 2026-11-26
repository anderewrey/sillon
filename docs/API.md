# Protocole Audio Station (API web Synology)


## Principe général
- Tout passe en HTTP(S) **POST form-urlencoded** sur `https://<nas>:<port>/webapi/<path>`.
- Chaque appel envoie : `api=<SYNO.xxx>`, `method=<m>`, `version=<n>`, `_sid=<session>`.
- Réponses JSON : `{ "success": bool, "data": {...}, "error": {"code": n} }`.
- Le `<path>` n'est pas figé : il se découvre via `SYNO.API.Info`.

## 1. Découverte des APIs
`GET/POST /webapi/query.cgi` avec `api=SYNO.API.Info&method=query&version=1&query=all`
→ renvoie pour chaque API `{path, minVersion, maxVersion}`.
L'app en déduit ses capacités (version max) :
| Capacité | Condition |
|---|---|
| Notation | `Song` >= v2 |
| Partage de playlists | `Playlist` >= v2 |
| Dossier récursif | `Folder` >= v3 |
| Genre > artiste | `Artist` >= v3 |
| Pin | `Pin` >= v1 |
| Ajouter ensuite | `RemotePlayer` >= v3 |
| Device de confiance | `SYNO.API.Auth` >= v6 |

## 2. Authentification
1. (optionnel) `SYNO.API.Encryption` / `getinfo` v1 : clé publique si le NAS impose le chiffrement du mot de passe.
2. `SYNO.API.Auth` / `login` (version = max supportée, 1 par défaut) avec :
   `account`, `passwd`, `session=AudioStation`, `client_time`,
   et pour la 2FA : `otp_code` (seulement quand un code est saisi), plus à **chaque** connexion
   `enable_device_token=yes`, `device_name` (toujours le même nom) et `device_id` s'il est connu.
3. La réponse contient le `sid` → à passer en `_sid` partout.
4. Déconnexion : `SYNO.API.Auth` / `logout` v1.
Note : v5 = OTP imposé, v6 = device de confiance (évite de redemander l'OTP).
L'identifiant d'appareil de confiance revient dans `data.did` (parfois `data.device_id`). DSM ne le reconnaît
que s'il est renvoyé avec le même `device_name` ; il peut être renouvelé à n'importe quelle connexion, il faut
donc toujours garder le dernier reçu (sinon le code OTP est redemandé au lancement suivant).

## 3. APIs Audio Station
`Info, Album, Artist, Composer, Cover, Folder, Genre, Lyrics, LyricsSearch, Pin,
Playlist, Proxy, Radio, RemotePlayer, RemotePlayerStatus, Song, Stream`
(toutes préfixées `SYNO.AudioStation.`)

Pagination : `offset` / `limit` (l'app utilise pages de 1024, `limit=-1` = tout).
Paramètre `library` : `personal` ou `shared`.

### Playlists (`SYNO.AudioStation.Playlist`) — déjà gérées par le serveur
| method | params | rôle |
|---|---|---|
| `list` | offset, limit, library (v2 si partage) | lister |
| `getinfo` | id, additional=songs,songs_total... | détail |
| `create` | name, library, songs=<ids> | créer |
| `updatesongs` | id, offset, limit, songs=<ids> | modifier le contenu (remplace la plage offset/limit) |
| `rename` | id, new_name | renommer |
| `delete` | id | supprimer |
| `updatesmart` | critères | playlist intelligente |
| `copytolibrary` | id | copier perso/partagée |
| `editsharing` (v2) | | partage |
=> **La limite "pas de playlists" est uniquement dans l'UI de l'app, pas dans l'API.**

### Lecture (`SYNO.AudioStation.Stream`)
- Direct : `/webapi/<path>?api=SYNO.AudioStation.Stream&method=stream&version=1&id=<songId>&_sid=<sid>&ext=.<ext>`
- Transcodage : `method=transcode&format=mp3|wav&bitrate=<kbps>&ext=.mp3`
- Chromecast : ajoute `&position=0`
- Streaming HTTP simple -> lisible tel quel par un lecteur natif (AVPlayer, ExoPlayer, mpv...).

### Pochettes
`SYNO.AudioStation.Cover` (`getsongcover`, `getfoldercover`, `getcover`).

### Autres
`Song` : `list`, `search`, `getinfo`, `setrating`, `getsharing`/`setsharing`.
`Folder` : `list` (navigation dossiers). `Radio` : `list`. `Lyrics` : `getlyrics`, `searchlyrics`.
`Pin` : `list`, `pin`, `unpin`, `rename`, `reorder`.
`RemotePlayer` / `RemotePlayerStatus` : contrôle des lecteurs distants (hors périmètre MVP).

## Confirmé sur le NAS réel (DSM, test du nas_probe.py)
- Chemins réels : `/webapi/AudioStation/<api>.cgi` (album, artist, song, stream, playlist, cover...) ;
  `SYNO.API.Auth`, `Encryption`, `Pin`, `Tag`, `Browse.Playlist` sont sur `/webapi/entry.cgi`.
  -> toujours passer par `query.cgi`, ne pas coder les chemins en dur.
- `SYNO.API.Auth` v1-v7 : login OK avec mot de passe en clair (HTTPS), `format=sid`, sans passer par `Encryption`.
- Versions : Song v1-3, Album v1-3, Artist v1-4, Playlist v1-3, Stream v1-2, Cover v1-3, Search v1 (API de recherche dédiée, absente de l'ancienne app).
- IDs : morceau `music_97817` ; playlist `playlist_personal_normal/<nom>` (la playlist système `__SYNO_AUDIO_SHARED_SONGS__` existe par défaut).
- `Song.list` avec `additional=song_tag,song_audio,song_rating` renvoie tags (album, album_artist, artist, comment...), audio (codec, bitrate, container, duration, filesize, frequency) et note.
- `Album.list` : `data.albums[]` + `data.total`.
- Info : `privilege.playlist_edit=true`, `enable_personal_library=true`, `playing_queue_max=8192`.
- Exemple de fichiers : AAC/mp4 ~129 kbps -> lisibles en direct par ExoPlayer, pas de transcodage nécessaire.
- CRUD playlist VALIDÉ : `create` (name, library=personal, songs=id1,id2) -> `data.id = "playlist_personal_normal/1"` ;
  `getinfo` (id, additional=songs,songs_total) -> `data.playlists[0].additional.songs[]` (id, path, title, type) + `songs_total` ;
  `delete` (id) -> `{"success":true,"data":{"errors":[]}}`.
- Nouveautés exploitables : `Download`, `Search`, `Tag` (édition de tags), `WebPlayer`.

## À vérifier avant de coder (pas encore confirmé dans le décompilé)
- Valeurs exactes du paramètre `additional` par endpoint (song_tag, song_audio, song_rating...).
- Format précis de chiffrement du mot de passe quand `Encryption` est actif.
- Endpoint `Song.list` : paramètres de tri/filtre (sort_by, sort_direction, filter).
- Les versions réelles exposées par TON NAS (`query.cgi?query=all`).

## Playlists : comportements vérifiés (Sillon, lot 3)
- `updatesongs(id, offset, limit, songs)` **remplace** `limit` morceaux à partir de `offset` par la liste `songs` :
  - ajouter en fin : `offset = total`, `limit = nombre ajouté`, `songs = ids` ;
  - retirer la position `i` : `offset = i`, `limit = 1`, `songs = ""` ;
  - réordonner : `offset = 0`, `limit = total`, `songs = ids dans le nouvel ordre`.
- `songs` : identifiants séparés par des virgules ; `\` et `,` dans un identifiant sont échappés par `\`.
- Un même morceau peut figurer plusieurs fois dans une playlist.
- `getinfo` ne renvoie **pas** de bloc `additional` si on demande seulement `songs_total` : il faut `additional=songs,songs_total`.
- Contenu complet : `getinfo` avec `additional=songs,songs_song_tag,songs_song_audio`, `songs_offset`, `songs_limit=-1`.
- `create` accepte `songs=""` (playlist vide). `rename` prend `id` et `new_name`.
- `list` avec `library=all` renvoie aussi la playlist système `__SYNO_AUDIO_SHARED_SONGS__` (masquée par Sillon).
- Pochette d'un morceau : `SYNO.AudioStation.Cover` / `getsongcover` / `id=<songId>`.

## Recherche et listes filtrées (vérifié, lot 4)
- Titres : `SYNO.AudioStation.Song` / `search` avec `title=<texte>` (+ `additional=song_tag,song_audio`). Recherche « contient », insensible à la casse.
- Albums : `SYNO.AudioStation.Album` / `list` avec `filter=<texte>` (« contient », insensible à la casse).
- Artistes : `SYNO.AudioStation.Artist` / `list` (`filter` accepté par l'ancienne app).
- Album d'un artiste : `Album.list` avec `artist=<nom>` ; genre : `genre=<nom>`.

## Artistes et genres (vérifié, lot 5)
- Artistes : `SYNO.AudioStation.Artist` / `list` avec `limit=-1` renvoie tous les artistes (`data.artists[].name`). `filter=<texte>` filtre par nom.
- Genres : `SYNO.AudioStation.Genre` / `list` avec `limit=-1` (`data.genres[].name`).
- Albums d'un artiste : `Album.list` + `artist=<nom>` ; d'un genre : `Album.list` + `genre=<nom>`.
- Titres d'un artiste : `Song.list` + `artist=<nom>`.

## Qualité de lecture et conversion (vérifié, lot 6)
- `SYNO.AudioStation.Info` / `getinfo` renvoie `data.transcode` : liste des formats de conversion proposés par le NAS (`mp3`, `wav`). L'ancienne app s'y fie avant de proposer une qualité.
- Conversion : `SYNO.AudioStation.Stream` / `transcode` avec `format=mp3`, `bitrate=<bits/s>` (320000, 192000, 128000) et `ext=.mp3`.
- Si le NAS ne propose pas la conversion demandée, il répond **HTTP 415** : ne pas la demander, lire l'original (`method=stream`).

## Retrouver des titres par identifiant (vérifié, lot 7)
- `SYNO.AudioStation.Song` / `getinfo` avec `id=<un seul identifiant>` renvoie le titre. Passer plusieurs identifiants séparés
  par des virgules ne renvoie **que le premier** : faire un appel par titre.
- `Song.list` renvoie `data.total` (nombre de titres, ou de titres du genre filtré) : base du tirage aléatoire par positions.

## Téléchargement (vérifié, lot 8)
- Un titre se télécharge avec la même requête que la lecture directe : `SYNO.AudioStation.Stream` / `stream` (qualité originale).
  Les octets reçus correspondent exactement à `filesize` annoncé par `song_audio`.
- Session expirée : le NAS répond **HTTP 200 avec du JSON** (et non une erreur HTTP) à la place de l'audio. Vérifier le
  `Content-Type` avant d'écrire le fichier, se reconnecter puis réessayer.

## Index de recherche local (lot 9)
- Le NAS ne cherche qu'un texte exact dans un seul champ. Sillon lit toute la bibliothèque par pages
  (`Song.list`, `limit=500`, `additional=song_tag,song_audio`) et la garde dans `library_index.json` (environ 540 Ko pour
  quelques milliers de titres).
- Mise à jour : l'index est relu quand le nombre de titres (`data.total`) change ou s'il a plus de 14 jours.
- `song_tag` fournit aussi `genre` et `year`, utilisés par la recherche.
