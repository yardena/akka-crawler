# akka-crawler

Main class is `CrawlerApp`, allows to invoke the crawler from command line.

`Crawler` is a singleton, root actor of the application, it is watched by `Terminator` that shuts down JVM when `Crawler` stops.

There are 2 important actors that do the actual work: 
* `Fetcher` is a singleton asynchronously performing HTTP requests using Akka Http Client and Akka Reactive Streams
* `Parser` is a singleton asynchronously extracting links from page HTML using JSouup

`Crawler` coordinates all the work: 
* it receives urls that need to be visited
* it checks that url hasn't been processed, or is being processed, or already enqueued for processing
* it creates `PageHandler` actors to process the url
* it tracks the lifecicle of `PageHandler` actors - in progress, success or failure
* it limits the number of active `PageHandler` actors by enqueuing urls when the threshold is exceeded, then by processing enqueued request after another `PageHandler` reaches a terminal state (success/failure).
* it outputs the result ratios
* holds the reference to `Fetcher` and `Parser` that it passes to `PageHandler` actors
