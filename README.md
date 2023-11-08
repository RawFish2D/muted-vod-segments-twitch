# Muted VOD segments detector for Twitch.tv

Uses Selenium to start the browser in headless mode. Opens a Twitch channel page to find all recent VODs.
Then searches for VODs with specific date, it gets this specific date from local VOD on host system. Then if VOD with this date found, gets the m3u8 file via streamlink and parses it to find muted segments.
After that, it uses ffmpeg to cut out unmuted segments from local VOD.

## Requirements
- Java 14+
- Streamlink
- ffmpeg

## Usage

```java -jar MutedVODDetector.jar -i "PATH_TO_DIRECTORY_WITH_LOCAL_VODS" -channel "CHANNEL_NAME" -o "OUTPUT_DIRECTORY_FOR_UNMUTED_CLIPS"```