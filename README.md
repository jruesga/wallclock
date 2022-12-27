# Description

A wall-clock live stream server using Skia and FFmpeg.

<img src="https://raw.githubusercontent.com/jruesga/wallclock/master/artwork/wallclock.png" width="800" height="452">

## Compile

```bash
./gradlew clean build
```

## Execute

```bash
java -jar build/libs/wallclock-0.1-SNAPSHOT-all.jar
```

# Usage

```bash
usage: WallClock [-h] [-v] [-vw=1920] [-vh=1080] [-fps=30] [-msd=-1] [-p=-1] --output-dir=<path>
 -h,--help                             display command line options
 -v,--version                          version
 -vw,--video-width <arg>               video width [default: 1920] (optional)
 -vh,--video-height <arg>              video height [default: 1080] (optional)
 -fps,--frames-per-second <arg>        video frames per second [default: 30] (optional)
 -msd,--max-streaming-duration <arg>   end streaming after x secs [default: -1] (optional)
 -p,--port <arg>                       enable streaming on port [default: -1] (optional)
 -d,--output-dir <arg>                 output directory (required)
```
