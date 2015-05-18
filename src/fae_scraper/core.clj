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
(def db-name "images")
(def db-tbl "fae")

(def cli-options
  ;; An option with a required argument
  [["-f" "--full" "Performs a full 'back in time' scrape before polling. Takes a long time."
    :default false]
   ["-p" "--page PAGENUM" "Specifies which page to poll."
    :default 1
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 0) "Must be a number greater than 0"]]
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
        "       Default behaviour is to periodically poll the FAE website for new art and add it to DB"
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
  (let [fae-contents (s/select
                      (s/child
                       (s/id "main")
                       (s/class "layout")
                       (s/tag :article)
                       (s/tag :div))
                      fae-page)]
    (mapv #(let [x %
                 href (-> (s/select (s/child (s/tag :a)) x) first :attrs :href)
                 desc (-> (s/select (s/child (s/tag :a)) x) first :content first :attrs :alt)]
             (if ;; if no desc then we're abnormal
               (not (nil? desc))
               {:href href :desc desc :iframe false}
               (let [href (-> (s/select (s/child (s/tag :iframe)) x) first :attrs :src)
                     desc (str "by " (-> (s/select (s/child (s/tag :a)) x) first :content first))]
                 (if ;; if no href then we're not a link, else we're an iframe
                   (not (nil? href))
                   {:href href
                    :desc (str (-> (s/select (s/child (s/tag :p)) x) first :content first :content first) " " desc)
                    :iframe true}
                   {:href (-> (s/select (s/child (s/tag :div)) x) first :content second :attrs :src)
                    :desc (-> (s/select (s/child (s/tag :div)) x) first :content second :attrs :alt)
                    :iframe false})))) ;; we're just an img
              fae-contents)))

;;
(defn fae-get-image-from-image-page
  "extracts the picture from a fae picture page"
  [page]
  (let [contents (-> (s/select (s/child
                     (s/id "content-image"))
                    page))]
             (:data-src (:attrs (first contents)))))

;;
(defn fae-get-images-from-iframe-page
  "extracts the pictures from a fae iframe page"
  [page]
  (let [contents (s/select
                  (s/child
                   (s/class :photoset)
                   (s/tag :div)
                   (s/tag :a))
                  page)]
             (mapv #(:href (:attrs %)) contents)))

;;
(defn- lazy-contains? [coll key]
  (boolean (some #(= % key) coll)))

;;
(defn fae-full-scrape-page
  "scrapes a given page"
  [page-num]
  (let [fae-stage-one-result (fae-extract-page-image-links (fae-get-list-page fae-base-url page-num))
        normal-links (filter #(not (:iframe %)) fae-stage-one-result)
        iframe-links (filter #(:iframe %) fae-stage-one-result)
        not-links    (filter #(.endsWith (:href %) ".jpg") normal-links)
        normal-links (filter #(not (lazy-contains? not-links %)) normal-links)
        data (flatten
              (conj
               (map #(dissoc (assoc % :src (fae-get-image-from-image-page   (get-page-as-hickory (:href %)))) :href) normal-links)
               (map #(dissoc (assoc % :src (fae-get-images-from-iframe-page (get-page-as-hickory (:href %)))) :href) iframe-links)
               (map #(dissoc (assoc % :src (:href %)) :href) not-links)))]
  (reduce
   (fn [v n] (if (:iframe n)
               (into v (map #(hash-map :src % :desc (:desc n)) (:src n)))
               (into v [(dissoc n :iframe)]))) [] data)))

;;
(defn process
  "takes a fae scrape output and stores/rejects it"
  [fae-map mg-db]
  (doseq [f fae-map]
    (if (not (image-exists? mg-db f))
      (do
        (add-image! mg-db (conj f {:scraped-date (java.util.Date.)}))
        (println (java.util.Date.) "Added" (:desc f) "...")))))


;; main
(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        full? (:full options)
        delay (:delay options)
        page-num (:page options)
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
        ;; back-in-time scrape
        (do
          (println "Performing a FULL scrape. This will take a while...")
          (let [last-result (atom '(1))
                page-count (atom 0)]
            (while (not (empty? @last-result))
              (do
                (swap! page-count inc)
                (println "Checking page" @page-count)
                (reset! last-result (fae-full-scrape-page @page-count))
                (process @last-result mg-db))))
          (println "Full scrape has completed! Resuming polling...")))

      ;; periodic scrape of page 1
      (every delay #(process (fae-full-scrape-page page-num) mg-db) my-pool :fixed-delay true :initial-delay 0))))


