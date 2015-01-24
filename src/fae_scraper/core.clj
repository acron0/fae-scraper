(ns fae-scraper.core
  (:require [clj-http.client :as client])
  (:require [hickory.select :as s])
  (:use hickory.core))

;; defines the url we use to extract the starting html
(def fae-base-url "http://fantasy-art-engine.tumblr.com/page/%d")

;; UTILITY for getting url as hickory - TODO move to util
(defn get-page-as-hickory [url]
  (-> (client/get url) :body parse as-hickory))

;; uses retreives the html and parses into a hickory structure
(defn fae-get-list-page [base-url page-num]
  (get-page-as-hickory (format base-url page-num)))

;; extracts the links from the hickory page structure
(defn fae-extract-page-image-links [fae-page]
  (let [fae-contents (-> (s/select (s/child
                     (s/id "main")
                     (s/class "layout")
                     (s/tag :article)
                     (s/tag :div) ;; some entries don't have links because the real picture is no bigger... need a separate routine for those.
                     (s/tag :a))
                    fae-page))]
    (vec(map #(assoc
                (:attrs %)
                :desc (:alt (:attrs (first (:content %)))))
             fae-contents))))

;; extracts the picture from a fae picture page
(defn fae-get-image-from-image-page [page]
  (let [contents (-> (s/select (s/child
                     (s/id "content-image"))
                    page))]
             (:data-src (:attrs (first contents)))))

;; main
(defn -main [& args]
  (println "Working....")
  ;;(println (fae-extract-page-image-links (fae-get-list-page fae-base-url 1))))
  (let [fae-stage-one-result (fae-extract-page-image-links (fae-get-list-page fae-base-url 1))]
    (println (map #(dissoc (assoc % :src (fae-get-image-from-image-page (get-page-as-hickory (:href %)))) :href) fae-stage-one-result))))
