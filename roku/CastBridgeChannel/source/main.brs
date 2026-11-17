sub Main(args as Dynamic)
    showChannelSGScreen(args)
end sub

sub showChannelSGScreen(args as Dynamic)
    screen = CreateObject("roSGScreen")
    m.port = CreateObject("roMessagePort")
    screen.SetMessagePort(m.port)

    scene = screen.CreateScene("MainScene")

    input = CreateObject("roInput")
    input.SetMessagePort(m.port)

    screen.Show()

    ' An ECP deep link that cold-starts the channel arrives in Main(args). A deep link delivered to
    ' an already running channel arrives later as an roInputEvent. roInput has no GetInfo() method:
    ' the initial info is only ever in args, so a launch from the home screen simply waits.
    launchArgs = invalid
    if args <> invalid and args.contentId <> invalid
        launchArgs = args
    end if

    print "CastBridge launch args: "; launchArgs
    if launchArgs <> invalid then scene.launchArgs = launchArgs

    while true
        msg = wait(0, m.port)
        msgType = type(msg)

        if msgType = "roSGScreenEvent"
            if msg.isScreenClosed() then return
        else if msgType = "roInputEvent"
            if msg.IsInput()
                info = msg.GetInfo()
                print "CastBridge input event: "; info
                if info <> invalid and info.contentId <> invalid
                    scene.launchArgs = info
                end if
            end if
        end if
    end while
end sub
