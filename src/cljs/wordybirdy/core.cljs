(ns wordybirdy.core
  (:require [om.core :as om :include-macros true]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [cljs.reader :as reader]
            [goog.events :as events]
            [wordybirdy.graphs :as graphs]))

(defonce app-state
  (atom
   {:graph-width "90%"
    :sentences []
    :wcount 0
    :lcount 0
    :ncount 0
    :graph-id "cy"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Graph Loaders

(defn load-graph-and-words! [app]
  (graphs/word-arc! (:graph-id app)
                    (:sentences app)))

(defn load-specific-graph! [type]
  (let [selector (str "node[type = \"" type "\"]")]
    (graphs/display-type! selector)))

(defn load-noun-graph! [_]
  (load-specific-graph! "noun"))

(defn load-adjective-graph! [_]
  (load-specific-graph! "adjective"))

(defn load-adverb-graph! [_]
  (load-specific-graph! "adverb"))

(defn load-verb-graph! [_]
  (load-specific-graph! "verb"))

(defn load-value-graph! [_]
  (load-specific-graph! "value"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Filter Helpers

(def valid-ints
  (apply hash-set
         (map #(->> % (str "\\") reader/read-string)
              (range 0 10))))

(defn suspect-number? [word]
  (when-not (nil? word)
    (every? (fn [n]
              (seq
               (filter valid-ints [n])))
            word)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Text Input Stat View

(defcomponent stat-view [app _]
  (render
   [_]
   (let [{:keys [wcount
                 lcount
                 ncount]
          :or {wcount 0
               lcount 0
               ncount 0}} app]
       (dom/div {:id "stats"}
                (dom/span "Words: " wcount " ")
                (dom/span ", Lines: " lcount " ")
                (dom/span ", Numbers: " ncount " ")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Text Input

(defn text-input-change! [evt app]
  (om/update! app [:txt] (-> evt .-target .-value)))

(defn text-input-lines [{:keys [txt]}]
  (let [lines (clojure.string/split-lines txt)]
    {:lines lines
     :count (count lines)}))

(defn text-input-words [{:keys [txt]}]
  (let [words (-> txt
                  (clojure.string/replace #"\s+" " ")
                  (clojure.string/split #" "))]
    {:words words
     :count (count words)}))

(defn text-input-numbers [{:keys [txt]}]
  (let [numbers (filter suspect-number?
                        (text-input-words {:txt txt}))]
    {:numbers numbers
     :count (count numbers)}))

(defn text-input-number-count [{:keys [txt]}]
  (count (text-input-number-count {:txt txt})))

(defn text-input-did-update [transactional-state]
  (let [ts transactional-state
        ncount (-> ts text-input-numbers :count)
        lcount (-> ts text-input-lines :count)
        wcount (-> ts text-input-words :count)
        wcount (- wcount ncount)
        sentences (-> js/nlp
                      (.pos (or (:txt ts) "none"))
                      .-sentences)
        sentences (loop [size (range (.-length sentences))
                         sentences* (transient [])]
                    (if-not (seq size)
                      (persistent! sentences*)
                      (recur
                       (rest size)
                       (conj! sentences*
                             (aget sentences (first size))))))]
    (-> ts
        (assoc-in [:sentences] sentences)
        (assoc-in [:wcount] wcount)
        (assoc-in [:lcount] lcount)
        (assoc-in [:ncount] ncount))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Text Input View

(defcomponent text-input-view [app owner]
  (did-update
   [_ _ _]
   (om/transact! app text-input-did-update))
  (render
   [_]
   (let [{:keys [txt]
          :or {txt ""}} app]
     (dom/textarea {:id "text-input"
                    :class "six columns"
                    :value txt
                    :on-change #(text-input-change! % app)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Menu View

(defcomponent menu-view [app owner]
  (render
   [_]
   (dom/div {:id "menu"}
            (dom/a {:class "option refresh"
                    :href "javascript:void(0);"
                    :on-click (fn [_] (load-graph-and-words! app))}
           "Reload*")
            (dom/a {:class "option noun"
                    :href "javascript:void(0);"
                    :on-click load-noun-graph!}
                   "Nouns")
            (dom/a {:class "option adjective"
                    :href "javascript:void(0);"
                    :on-click load-adjective-graph!}
                   "Adjectives")
            (dom/a {:class "option adverb"
                    :href "javascript:void(0);"
                    :on-click load-adverb-graph!}
                   "Adverbs")
            (dom/a {:class "option verb"
                    :href "javascript:void(0);"
                    :on-click load-verb-graph!}
                   "Verbs")
            (dom/a {:class "option value"
                    :href "javascript:void(0);"
                    :on-click load-value-graph!}
                   "Values"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Single Sentence View 

(defcomponent single-sentence-view [{:keys [s]} _]
  (render
   [_]          
   (dom/p
    (for [t (.-tokens s)
          :let [type (-> t .-pos .-parent)
                text (.-text t)
                text (or text
                         (-> t .-analysis .-word))
                node-id (.-normalised t)]]
      (dom/a {:href "javascript:void(0);"
              :on-click (fn [evt]
                          (graphs/word-arc-node-tap! evt node-id))}
             (dom/span {:class type} text " "))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Sentences View

(defcomponent sentences-view [app _]
  (render
   [_]
   (dom/div {:class "six columns"
             :style {:padding "10px"}}
            (if-let [ss (seq (:sentences app))]
              (lazy-seq
               (om/build-all single-sentence-view
                             (map #(hash-map :s %) ss)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Export Functions 

(defn export-png! [_]
  (let [msg "Export only the visible in the view?"
        full? (not (boolean (js/confirm msg)))
        msg (str "Background Color: The background "
                 "is transparent on cancel")
        color (js/prompt msg)
        msg (str "Image Scale")
        scale (js/prompt msg 0.8)
        options (clj->js
                 (as-> {} options
                   (assoc options
                          :full full?
                          :scale scale)
                   (if-not color
                     options
                     (assoc options :bg color))))
        image (-> js/window .-cy (.png options))]
    (.open js/window image)))

(defn json []
  (.stringify js/JSON
              (-> js/window .-cy .json)))

(defn export-json! [_]
  (js/prompt "Copy your JSON below"
             (json)))

(defn edn []
  (letfn [(edn-keys [m]
            (reduce merge
                    (for [[k v] m
                          :let [v (if-not (map? v)
                                    v
                                    (edn-keys v))]]
                      (hash-map (keyword k) v))))]
    (->> js/window .-cy .json js->clj edn-keys)))

(defn export-edn! [_]
  (js/prompt "Copy your EDN below"
             (edn)))

(defcomponent export-view [& _]
  (render
   [_]
   (dom/div
    {:id "export"
     :style {"text-align" "right"}}
    "Export as "
    (dom/a {:href "javascript:void(0);"
            :on-click export-png!}
           "PNG")
    " , "
    (dom/a {:href "javascript:void(0);"
            :on-click export-json!}
           "JSON")
    " , "
    (dom/a {:href "javascript:void(0);"
            :on-click export-edn!}
           "EDN"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Application View

(defcomponent main-view [app owner]  
  (render
   [_]
   (dom/div {:id "main-view"}            
            (om/build menu-view app)
            (dom/div 
             (om/build stat-view app)
             (om/build export-view app)
             (dom/div {:style {:height (:graph-height app)}
                       :id (:graph-id app)})
             (om/build text-input-view app)
             (om/build sentences-view app)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Application Run 

(defn main []
  (om/root
   main-view
   app-state
   {:target (. js/document (getElementById "app"))}))
