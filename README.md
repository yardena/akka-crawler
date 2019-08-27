# akka-crawler

Main class is `CrawlerApp`, allows to invoke the crawler from command line.

`Crawler` is a singleton, root actor of the application, it is watched by `Terminator` that shuts down JVM when `Crawler` stops.

There are 2 important actors that do the actual work: 
* `Fetcher` is a singleton asynchronously performing HTTP requests using Akka Http Client and Akka Reactive Streams
* `Parser` is a singleton asynchronously extracting links from page HTML using JSoup

Each of these actors have different dedicated executors (thread pools) that suit the task of the actor, sandbox it, and can be independently tuned.

### `Crawler` coordinates all the work: 
* it receives urls that need to be visited
* it checks that url hasn't been processed, or is being processed, or already enqueued for processing
* it creates `PageHandler` actors to process the url
* it tracks the lifecicle of `PageHandler` actors - in progress, success or failure
* it limits the number of active `PageHandler` actors by enqueuing urls when the threshold is exceeded, then by processing enqueued request after another `PageHandler` reaches a terminal state (success/failure).
* it outputs the result ratios
* holds the reference to `Fetcher` and `Parser` that it passes to `PageHandler` actors

### `PageHandler` is responsible for the work on a single link:
* it checks if the link has already been processed in a previous `Crawler` run, with the help of `PageCache` actor, and also validates if the resut is recent or needs to be re-fetched
* it limits the time for page processing, stoping itself if that time has been exceeded
* it communicates with `Fetcher` sending the requests and receiving its response
* it saves the downloaded HTML to a file on the disk, by default into `./data` directory (this may have been extracted into another dedicated actor for better modularity)
* it handles different response codes including following redirects (but limiting the number of times request is redirected)
* it sends response body to the `Parser`, asynchronously receiving back all the links
* it calculates the ratio of same-domain links to total links
* it knows the depth of the page, and max depth for crawling, so it sends the extracted links to its parent, the `Crawler`, for further handling if max depth hasn't been exceeded
* it stores the result using `PageCache`

### `Fetcher`
Handles requests asynchronously, returning response bodies.

All requests are pipelined through a flow that consists of the following elements:
1. Queue, to buffer requests when Http Client **backpressures**
1. Create HTTP request, including appropriate headers
1. Group of host-based http connection pools (Akka's "super-pool")
1. Basic classification of the response by **status code** and **content type**
1. Extract response body and return it to sender

The flow tracks sender of each request by using a pass-through context

### `Parser`
* Since JSoup is synchronous, `Parser` invokes it asynchronously for each page
* it filters links to include only http(s) ones
* it sends them back one-by-one to allow better asynchronous processing of the links

### `PageCache`
Using `akka-persistence` module this actor saves messages it receives and can re-play them when re-started.
In order to be able to do it, the actor needs a unique `persistenceId` - Hashids library is used to generate id from url.
When page is processed, the result is sent to `PageCache` and persisted to the journal.
Whenever `PageCache` is created, `akka-persistence` will look for the journal with the id, and send the messages in the journal to the actor allowing it to recover the last state. In the meantime messages to the actor are stashed and the actor will receive them after the state was recovered.

LevelDB plugin is used for persistence and Kryo plugin is used to serialize/de-serialize the state.
By default, `journal` and `snapshot` are stored as sub-dirs of current directory.

## Infratructure
Log file is written to current directory, summary of Dropwizard Metrics will be reported in the log when Crawler terminates.
Environment variables can be used to costomise some of the behavior: `MAX_PAGES_IN_FLIGHT`, `FETCH_QUEUE_SIZE`, `SAVE_DIR`, `CHECK_PAGE_MODIFIED_AFTER`, `PAGE_PROCESSING_TIMEOUT`. 

## Further improvements
* Tests!!!
* Better file saving (separate from `PageHandler` and streamline)
* Respect `robots.txt`


