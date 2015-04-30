(use 'fae-scraper.core)

(require '[clj-http.client :as client])
(require ' [hickory.select :as s])
(require '[clojure.string :as string])

(def page-num 34)

(defn fae-extract-page-image-links2
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

(defn lazy-contains? [coll key]
  (boolean (some #(= % key) coll)))

(defn fae-full-scrape-page2
  "scrapes a given page"
  [page-num]
  (let [fae-stage-one-result (fae-extract-page-image-links2 (fae-get-list-page fae-base-url page-num))
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

(fae-full-scrape-page2 page-num)
