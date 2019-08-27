# akka-crawler

Main class is `CrawlerApp`, allows to invoke the crawler from command line.

`Crawler` is a singleton, root actor of the application, it is watched by `Terminator` that shuts down JVM when `Crawler` stops.

There are 2 important actors that do the actual work: 
* `Fetcher` is a singleton asynchronously performing HTTP requests using Akka Http Client and Akka Reactive Streams
* `Parser` is a singleton asynchronously extracting links from page HTML using JSoup
Each of these actors have different dedicated executors (thread pools) that suit the task of the actor, sandbox it, and can be independently tuned.

`Crawler` coordinates all the work: 
* it receives urls that need to be visited
* it checks that url hasn't been processed, or is being processed, or already enqueued for processing
* it creates `PageHandler` actors to process the url
* it tracks the lifecicle of `PageHandler` actors - in progress, success or failure
* it limits the number of active `PageHandler` actors by enqueuing urls when the threshold is exceeded, then by processing enqueued request after another `PageHandler` reaches a terminal state (success/failure).
* it outputs the result ratios
* holds the reference to `Fetcher` and `Parser` that it passes to `PageHandler` actors

`PageHandler` is responsible for the work on a single link:
* it checks if the link has already been processed in a previous `Crawler` run, with the help of `PageCache` actor, and also validates if the resut is recent or needs to be re-fetched
* it limits the time for page processing, stoping itself if that time has been exceeded
* it communicates with `Fetcher` sending the requests and receiving its response
* it saves the downloaded HTML to a file on the disk (this may have been extracted into another dedicated actor for better modularity)
* it handles different response codes including following redirects (but limiting the number of times request is redirected)
* it sends response body to the `Parser`, asynchronously receiving back all the links
* it calculates the ratio of same-domain links to total links
