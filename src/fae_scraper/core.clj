(ns fae-scraper.core
  (:require [clj-http.client :as client]
            [hickory.select :as s]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [monger.core :as mg]
            [monger.collection :as mc])
  (:use [overtone.at-at]
        [hickory.core])
  (:gen-class))

;; constants
(def title "Fantasy Art Engine Scraper")                           ;; title of the app
(def my-pool (mk-pool))                                            ;; thread pool for at-at
(def fae-base-url "http://fantasy-art-engine.tumblr.com/page/%d")  ;; defines the url we use to extract the starting html
(def db-name "fae")
(def db-tbl "images")

(def cli-options
  ;; An option with a required argument
  [["-f" "--full" "Performs a full 'back in time' scrape before polling. Takes a long time."
    :default false]
   ["-d" "--delay MILLISECONDS" "Millisecond delay between polls"
    :default 3600000 ;; default is 1 hr
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 1000) "Must be a number greater than 1000"]]
   ["-h" "--help" "Displays usage"]])

(defn usage [options-summary]
  "Prints the program usage"
  (->> [title
        ""
        "Usage: fae-scraper [options]"
        "       Default behaviour is to periodically poll thw FAE website for new art and add it to DB"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn error-msg
  "Appropriately prints an error message"
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit
  "Exits the program with a message"
  [status msg]
  (println msg)
  (System/exit status))

;; -------------------------------------------------


(defn image-exists?
  "checks to see if an image exists in the db already"
  [db src]
    (not (empty? (mc/find-maps db db-tbl {:src (:src src)}))))

(defn add-image!
  "Adds a image to the images table"
  [db image]
  (mc/insert-and-return db db-tbl image))

;; -------------------------------------------------

;;
(defn get-page-as-hickory
  "UTILITY for getting url as hickory - TODO move to util"
  [url]
  (-> (client/get url) :body parse as-hickory))

;;
(defn fae-get-list-page
  "retreives the html and parses into a hickory structure"
  [base-url page-num]
  (get-page-as-hickory (format base-url page-num)))

;;
(defn fae-extract-page-image-links
  "extracts the links from the hickory page structure"
  [fae-page]
  (let [fae-contents (-> (s/select (s/child
                     (s/id "main")
                     (s/class "layout")
                     (s/tag :article)
                     (s/tag :div) ;; TODO some entries don't have links because the real picture is no bigger... need a separate routine for those.
                     (s/tag :a))
                    fae-page))]
    (vec(map #(assoc
                (:attrs %)
                :desc (:alt (:attrs (first (:content %)))))
             fae-contents))))

;;
(defn fae-get-image-from-image-page
  "extracts the picture from a fae picture page"
  [page]
  (let [contents (-> (s/select (s/child
                     (s/id "content-image"))
                    page))]
             (:data-src (:attrs (first contents)))))

(defn fae-full-scrape-page
  "scrapes a given page"
  [pageNum]
  (let [fae-stage-one-result (fae-extract-page-image-links (fae-get-list-page fae-base-url pageNum))]
    (map #(dissoc (assoc % :src (fae-get-image-from-image-page (get-page-as-hickory (:href %)))) :href) fae-stage-one-result)))

(defn process
  "takes a fae scrape output and stores/rejects it"
  [fae-map mg-db]
  (doseq [f fae-map]
    (if (not (image-exists? mg-db f))
      (do
        (add-image! mg-db f)
        (println (java.util.Date.) "Added" (:desc f) "...")))))


;; main
(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        full? (:full options)
        delay (:delay options)
        mg-conn (mg/connect)
        mg-db (mg/get-db mg-conn db-name)]
    (do
      (cond
        (:help options) (exit 0 (usage summary))
        ;;(nil? name) (exit 0 (usage summary))
        ;;(nil? region) (exit 0 (usage summary))
        errors (exit 1 (error-msg errors)))
      (println title "is starting...")

      (if full?
        (do
          (println "Performing a FULL scrape. This will take a while...")))

      ;; periodic scrape of page 1
      (every delay #(process (fae-full-scrape-page 1) mg-db) my-pool :fixed-delay true :initial-delay 0))))


