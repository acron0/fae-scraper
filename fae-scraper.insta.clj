(use 'fae-scraper.core)

(require '[clj-http.client :as client])
(require ' [hickory.select :as s])
(require '[clojure.string :as string])

(def page-num 7)

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

(let [fae-stage-one-result (fae-extract-page-image-links (fae-get-list-page fae-base-url page-num))
      normal-links (filter #(not (:iframe %)) fae-stage-one-result)
      iframe-links (filter #(:iframe %) fae-stage-one-result)
      data (flatten
               (conj
                 (map #(dissoc (assoc % :src (fae-get-image-from-image-page   (get-page-as-hickory (:href %)))) :href) normal-links)
                 (map #(dissoc (assoc % :src (fae-get-images-from-iframe-page (get-page-as-hickory (:href %)))) :href) iframe-links)))]
  (reduce
   (fn [v n] (if (:iframe n)
               (into v (map #(hash-map :src % :desc (:desc n)) (:src n)))
               (into v [(dissoc n :iframe)]))) [] data))
