;; Anything you type in here will be executed
;; immediately with the results shown on the
;; right.

(ns fae-scraper.core
  (:require [clj-http.client :as client])
  (:require [hickory.select :as s])
  (:use hickory.core))

(defn get-page-as-hickory [url]
  (-> (client/get url) :body parse as-hickory))

;; defines the url we use to extract the starting html
(def fae-base-url "http://fantasy-art-engine.tumblr.com/page/%d")

;; uses retreives the html and parses into a hickory structure
(defn fae-get-list-page [base-url page-num]
  (get-page-as-hickory (format base-url page-num)))

;; extracts the links from the hickory page structure
(defn fae-extract-page-image-links [page]
  (let [contents (-> (s/select (s/child
                     (s/id "main")
                     (s/class "layout")
                     (s/tag :article)
                     (s/tag :div) ;; some entries don't have links because the real picture is no bigger... need a separate routine for those.
                     (s/tag :a))
                    page))]
    (vec(map #(assoc
                (:attrs %)
                :desc (:alt (:attrs (first (:content %)))))
             contents))))

(def fae-stage-one-result (fae-extract-page-image-links (fae-get-list-page fae-base-url 1)))

(defn fae-get-image-from-image-page [page]
  (let [contents (-> (s/select (s/child
                     (s/id "content-image"))
                    page))]
             (:data-src (:attrs (first contents)))))

(map #(dissoc (assoc % :src (fae-get-image-from-image-page (get-page-as-hickory (:href %)))) :href) fae-stage-one-result)
