# fae-scraper
Scraper for Fantasy Art Engine - http://fantasy-art-engine.tumblr.com/

### Prerequisites

* Local MongoDB with default port.

### Run

    Usage: fae-scraper [options]
       Default behaviour is to periodically poll the FAE website for new art and add it to DB

    Options:
      -f, --full                         Performs a full 'back in time' scrape before polling. Takes a long time.
      -p, --page PAGENUM        1        Specifies which page to poll.
      -d, --delay MILLISECONDS  3600000  Millisecond delay between polls
      -h, --help                         Displays usage
      
