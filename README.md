# Trading App

## Architecture

I wanted to explore the Elm architecture and Redux way of building apps on Android. It is heavily
inspired by this talk:
[Managing State with RxJava](https://speakerdeck.com/jakewharton/the-state-of-managing-state-with-rxjava-devoxx-us-2017).
The naming conventions are the same and might look weird for people not familiar the aforementioned
frameworks, in a nutshell:

* Model: defines common types and data structures
* View: defines how data is displayed - that is in Android lingo an `Activity`, a `Fragment`, a `View`
* Update: defines interactions, it's responsible for most of the state handling and app logic

Those three files encapsulate classes related to those components of the architecture, the `RestApi`
and `WebSocketApi` files contain code that handles a trading API.

## Tradeoffs
- UI is minimal since it was not the focus of the assignment
- I found handling websockets with Rx was particularly challenging and I'm not 100% convinced the way
I've done it it's the cleanest, clearest and more elegan; it probably need a couple more iterations.

## Improvements
I don't know if you're familiar with Reactive Extensions and RxJava but I couldn't find elegant
solutions in a couple of places (marked with TODO#Improve). In case you are familiar with it, I'd
 appreciate some feedback on that if you've got any.

# Known bugs
If you're testing the app using an emulator, fetching a new product id won't work because of [this
 issue](https://github.com/square/okhttp/issues/3278) in okhttp

## Building the app
I used Gradle and all standard Android tools so you shouldn't have any issues with it.
