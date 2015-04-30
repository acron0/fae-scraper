(use 'fae-scraper.core)

(def page1 (fae-full-scrape-page 1))

(require '[monger.core :as mg])
(require '[monger.collection :as mc])

(def mg-conn (mg/connect))
(def mg-db (mg/get-db mg-conn db-name))

(defn process2
  "takes a fae scrape output and stores/rejects it"
  [fae-map mg-db]
  (doseq [f fae-map]
    (if (not (image-exists? mg-db f))
      (do
        (add-image! mg-db f)
        (println (java.util.Date.) "Added" (:desc f) "...")))))

;;(process2 page1 mg-db)

(image-exists? mg-db (first page1))
