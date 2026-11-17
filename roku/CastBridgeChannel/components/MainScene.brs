sub init()
    print "CastBridge MainScene init"
    m.video = m.top.findNode("video")
    m.message = m.top.findNode("message")
    m.hint = m.top.findNode("hint")
    m.code = m.top.findNode("code")

    ' Centre the labels on the 1280x720 scene.
    m.message.translation = [90, 280]
    m.hint.translation = [90, 380]
    m.code.translation = [0, 670]

    m.video.observeField("state", "onVideoState")
    m.video.SetFocus(true)

    m.streamUrl = ""
    m.retryCount = 0
    m.stallSeconds = 0

    ' Deep-link parameters are handed over as a field so they work no matter whether they arrive
    ' before or after the scene finishes initialising.
    m.top.observeField("launchArgs", "onLaunchArgs")

    ' A dev channel is relaunched from scratch on every ECP launch, so the scene always runs init()
    ' just before the stream URL arrives. Showing "Waiting for your phone..." immediately would make
    ' that text flash on screen on every transition, so only show it if no launch follows.
    m.startupTimer = CreateTimer(1.5, false, "onStartupTimer")
    m.startupTimer.control = "start"

    ' The phone may still be creating the first HLS segment when the TV asks for the playlist, and a
    ' dev-channel relaunch can briefly leave the old stream in an error state. Retry a few times
    ' instead of giving up on the first hiccup.
    m.retryTimer = CreateTimer(2.0, false, "onRetryTimer")
    m.watchdog = CreateTimer(3.0, true, "onWatchdog")
end sub

sub onLaunchArgs()
    print "CastBridge onLaunchArgs"
    HandleLaunchArgs(m.top.launchArgs)
end sub

sub HandleLaunchArgs(args as Dynamic)
    print "CastBridge HandleLaunchArgs"
    if args = invalid
        print "CastBridge HandleLaunchArgs: args invalid"
        return
    end if

    contentId = args.contentId
    print "CastBridge HandleLaunchArgs contentId="; contentId
    if contentId = invalid or contentId = "" then return

    text = contentId
    if Instr(1, text, "%") > 0 then
        transfer = CreateObject("roUrlTransfer")
        text = transfer.Unescape(text)
    end if
    print "CastBridge HandleLaunchArgs text="; text

    if text = "castbridge-stop"
        StopPlayback()
        ShowMessage("Cast stopped.", "Start streaming again in the CastBridge app.")
        return
    end if

    if Left(text, 4) = "http" then
        PlayStream(text)
    end if
end sub

sub PlayStream(url as String)
    print "CastBridge PlayStream "; url
    if m.video = invalid
        print "CastBridge PlayStream: video node missing"
        return
    end if

    m.startupTimer.control = "stop"
    m.retryTimer.control = "stop"

    m.streamUrl = url
    m.retryCount = 0
    m.stallSeconds = 0

    code = ParsePairing(url)
    if code <> ""
        m.code.text = "Pairing code " + code
        m.code.visible = true
    else
        m.code.visible = false
    end if

    ApplyGeometry(url)
    StartPlayback()
end sub

sub StartPlayback()
    if m.video = invalid or m.streamUrl = "" then return

    m.video.visible = true
    m.message.visible = false
    m.hint.visible = false

    ' A Video node has no "url" field of its own: it plays the url of a ContentNode. Assigning a
    ' fresh ContentNode forces a reload even if the node was already playing something.
    content = CreateObject("roSGNode", "ContentNode")
    content.url = m.streamUrl
    content.streamFormat = "hls"
    m.video.content = content
    m.video.control = "play"
    print "CastBridge StartPlayback control=play attempt="; m.retryCount
end sub

sub StopPlayback()
    m.streamUrl = ""
    m.retryCount = 0
    m.stallSeconds = 0
    if m.retryTimer <> invalid then m.retryTimer.control = "stop"

    if m.video <> invalid
        m.video.control = "stop"
        m.video.content = invalid
        m.video.visible = false
    end if
    m.code.visible = false
end sub

sub onVideoState()
    if m.video = invalid then return

    state = m.video.state
    print "CastBridge video state="; state; " errorMsg="; m.video.errorMsg

    if state = "error"
        RetryPlayback("error")
    else if state = "playing"
        m.retryCount = 0
        m.stallSeconds = 0
    end if
end sub

sub onStartupTimer()
    if m.streamUrl = "" then
        ShowMessage("Waiting for your phone...", "Open CastBridge and tap Play on this TV.")
    end if
end sub

sub onWatchdog()
    if m.video = invalid or m.streamUrl = "" then return

    state = m.video.state
    if state = "playing"
        m.retryCount = 0
        m.stallSeconds = 0
        return
    end if

    if state = "error"
        RetryPlayback("error")
        return
    end if

    ' Still loading, buffering, or starting with nothing played yet: the first segment may not
    ' exist on the phone. Give it a few seconds, then reconnect.
    if m.video.position <= 0
        m.stallSeconds = m.stallSeconds + 3
        if m.stallSeconds >= 12 then RetryPlayback("stalled")
    else
        m.stallSeconds = 0
    end if
end sub

sub RetryPlayback(reason as String)
    print "CastBridge RetryPlayback reason="; reason; " attempt="; m.retryCount
    m.stallSeconds = 0

    if m.retryCount >= 3
        StopPlayback()
        ShowMessage("Stream stopped.", "Start streaming again in the CastBridge app.")
        return
    end if

    m.retryCount = m.retryCount + 1
    m.video.control = "stop"
    m.video.content = invalid

    ShowMessage("Connecting to your phone...", "Attempt " + m.retryCount.ToStr() + " of 3")
    m.retryTimer.control = "start"
end sub

sub onRetryTimer()
    if m.streamUrl = "" then return
    StartPlayback()
end sub

' Places the portrait phone screen on the 1280x720 TV screen.
'
' Roku always keeps the video's aspect ratio inside the Video node, so the node itself is sized to
' the stream's aspect ratio and positioned on the scene:
'   fit  (default) - the whole phone screen is visible, with black bars at the sides.
'   fill (fill=1)  - the phone screen covers the TV; the top and bottom are cropped.
' Every value is rounded to whole pixels: a fractional node size or offset makes the scaler jitter,
' which shows up as the picture shaking.
sub ApplyGeometry(url as String)
    w = ParseIntParam(url, "w")
    h = ParseIntParam(url, "h")
    if w <= 0 or h <= 0
        ' Older app builds do not send the size; assume a portrait phone screen.
        w = 886
        h = 1920
    end if
    fill = ParseIntParam(url, "fill") = 1

    screenW = 1280.0
    screenH = 720.0
    videoAspect = w / h
    screenAspect = screenW / screenH

    if fill
        if videoAspect < screenAspect
            ' Taller than the screen: match the width, crop the top and bottom.
            nodeW = screenW
            nodeH = screenW / videoAspect
        else
            ' Wider than the screen: match the height, crop the left and right.
            nodeW = screenH * videoAspect
            nodeH = screenH
        end if
    else
        if videoAspect < screenAspect
            ' Taller than the screen: match the height, letterbox left and right.
            nodeH = screenH
            nodeW = screenH * videoAspect
        else
            ' Wider than the screen: match the width, letterbox top and bottom.
            nodeW = screenW
            nodeH = screenW / videoAspect
        end if
    end if

    nodeW = RoundInt(nodeW)
    nodeH = RoundInt(nodeH)
    nodeX = RoundInt((screenW - nodeW) / 2)
    nodeY = RoundInt((screenH - nodeH) / 2)

    m.video.width = nodeW
    m.video.height = nodeH
    m.video.translation = [nodeX, nodeY]
    print "CastBridge geometry "; w; "x"; h; " fill="; fill; " -> "; nodeW; "x"; nodeH; " at "; m.video.translation
end sub

' Rounds half away from zero. BrightScript's Int() truncates, which would drift the offset by a
' pixel on negative translations (the fill crop above the screen).
function RoundInt(value as Float) as Integer
    if value < 0 then return -Int(-value + 0.5)
    return Int(value + 0.5)
end function

function CreateTimer(seconds as Float, repeat as Boolean, handler as String) as Object
    timer = CreateObject("roSGNode", "Timer")
    timer.duration = seconds
    timer.repeat = repeat
    timer.observeField("fire", handler)
    m.top.appendChild(timer)
    if repeat then timer.control = "start"
    return timer
end function

sub ShowMessage(title as String, hint as String)
    m.message.text = title
    m.message.visible = true

    m.hint.text = hint
    m.hint.visible = (hint <> "")
end sub

function ParsePairing(url as String) as String
    marker = "pairing="
    index = Instr(1, url, marker)
    if index = 0 then return ""

    start = index + Len(marker)
    return Mid(url, start, 6)
end function

function ParseIntParam(url as String, name as String) as Integer
    marker = "?" + name + "="
    index = Instr(1, url, marker)
    if index = 0
        marker = "&" + name + "="
        index = Instr(1, url, marker)
    end if
    if index = 0 then return 0

    start = index + Len(marker)
    value = 0
    for i = start to Len(url)
        ch = Mid(url, i, 1)
        if ch < "0" or ch > "9" then exit for
        value = value * 10 + (Asc(ch) - 48)
    end for
    return value
end function
