(ns wordybirdy.graphs)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Customer Selector Display

(defn display-type! [type-selector]
  (let [cy (.-cy js/window)]
    (-> cy (.elements "node") .hide)
    (-> cy (.elements "edge") .hide)
    (-> cy (.elements type-selector) .show)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Selector Format Helpers

(defn selector-escape [word]
  (loop [escape "$\\/()|?+*[]{},."
         word [word]]
    (if-let [e (first escape)]
      (let [esc (str "\\" e)
            word [(clojure.string/replace (first word)
                                          e
                                          esc)]]
        (recur
         (rest escape)
         word))
      (first word))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Elements

(declare node-color)

(defn element-node [nlp-token]
  (let [w nlp-token
        id (aget w "normalised")
        name id
        id (selector-escape id)
        type (aget w "pos" "parent")]
    {:data {:id id
            :name name
            :type type
            :color (node-color type)}}))

(defn element-words [sentences]
  (map (fn [ws]
         (for [n (range (.-length ws))]
           (aget ws n)))
       sentences))

(defn element-nodes [sentences]
  (->> sentences
       element-words
       (mapcat #(map element-node %))
       (apply hash-set)))

(defn element-edges* [sentences]
  (let [t (transient #{})
        edge (fn [s t] {:data {:source s :target t}})]
    (doseq [ws (element-words sentences)]
      (doseq [w ws]
        (let [last (aget w "analysis" "last")
              next (aget w "analysis" "next")
              w (aget w "normalised")]                      
          (if next
            (conj! t (edge w (aget next "normalised"))))
          (if last
            (conj! t (edge (aget last "normalised") w))))))
    (persistent! t)))

(defn element-edges [sentences]
  (map (fn [edge]
         (let [id (gensym (str "edge-"
                               (get-in edge [:data :source])))]
           (assoc edge :id id)))
       (element-edges* sentences)))

(defn word-arc-elements [sentences]
  (let [sentences (map #(.-tokens %) sentences)
        nodes (element-nodes sentences)
        edges (element-edges sentences)]
    {:nodes nodes :edges edges}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Styles

(def color-array
  (clj->js
   {:noun "hsl(200,100%,60%)"
    :adjective "hsl(100,100%,60%)"
    :adverb "hsl(30,100%,60%)"
    :verb "hsl(60,100%,60%)"
    :value "hsl(295,100%,60%)"}))

(defn node-color [type]
  (or (aget color-array type)
      "#FFFFFF"))

(def word-arc-style
  (let [ss (.stylesheet js/cytoscape)]
    (doto ss
      (.selector "node")
      (.css (clj->js {:content "data(name)"
                      :shape "circle"
                      :text-valign "center"
                      :background-color "#000"
                      :border-color "hsl(200,20%,70%)"
                      :border-style "solid"
                      :border-width "20"
                      :font-size "100"
                      :font-weight "bold"
                      :color "data(color)"
                      :margin "200px"
                      :width "500px"
                      :height "500px"
                      }))
      (.selector ":selected")
      (.css (clj->js {:border-width "8px"
                      :border-color "hsl(0,100%,60%)"
                      :line-color "hsl(0,100%,60%)"
                      :border-style "dotted"}))
      (.selector "edge")
      (.css (clj->js {:line-color "#FFFFFF"
                      :width "30px"
                      :display "none"
                      :z-index 100
                      :curve-style "bezier"
                      :source-arrow-shape "circle"
                      :target-arrow-shape "triangle-tee"
                      :target-arrow-color "hsl(0,100%,60%)"
                      :mid-target-arrow-shape "diamond"})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Node Selection 

(defn selector-node-edges [node-id]
  (str "edge[source = \""
       (selector-escape node-id)
       "\"], edge[target = \""
       (selector-escape node-id)
       "\"]"))

(defn edges [node-id]
  (-> js/window
      .-cy
      (.elements (selector-node-edges node-id))))

(defn neighbors [node-id]
  (-> node-id
      edges
     .closedNeighborhood
     .connectedNodes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Node Selection Event Handlers

(let [center-x 400
      center-y 100
      apos #js {:x center-x :y center-y}
      previous (atom
                {:node false
                 :positions apos})]

  (defn position-swap! [node]
    (let [p @previous
          pnode (:node p)]
      (when-not (= pnode node)
        (if pnode
          (doto pnode
            (.position (:positions p))
            .unselect))
        (swap! previous
               assoc
               :node node
               :positions #js {:x (.position node "x")
                               :y (.position node "y")})
        (.position node apos))))
  
  (defn word-arc-node-tap! [evt & [node-id]]
    (this-as
     this
     (let [cy (.-cy js/window)
           node (if-not node-id
                  (.-cyTarget evt)
                  (-> cy
                      (.filter (str "#"
                                    (selector-escape node-id)))))
           node-id (.id node)
           selector (selector-node-edges node-id)
           edges (edges node-id)
           neighbors (neighbors node-id)
           nlength (.-length neighbors)]       
       (position-swap! node)
       (-> cy (.elements "edge") .hide)
       (-> cy (.elements "node") .hide)
       (.show edges)
       (doto node .show .select)
       (when-not (zero? nlength)
         (.show neighbors)
         (doto cy
           (.center neighbors)
           (.fit neighbors))))))
  
  (defn word-arc-node-unselect! [evt]
    (this-as
     this
     (if (= (.-cyTarget evt) (:node @previous))
       (let [cy (.-cy js/window)
             nodes (.elements cy "node")]
         (.show nodes)
         (doto cy
           .fit
           .center))))))

(defn word-arc-ready! [evt]
  (this-as
   this
   (set! (.-cy js/window)
         (doto this
           (.on "unselect" "node" #{} word-arc-node-unselect!)
           (.on "tap" "node" #js {} word-arc-node-tap!)
           .fit))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Graph Application Run

(defn word-arc! [el-id sentences]
  (let [el (.getElementById js/document el-id)]
    (js/cytoscape
     (clj->js
      {:container el
       :layout #js {:name "cose"
                    :nodeSpacing: (fn [node] 20)
                    :edgeLength (fn [edge] 10)
                    :handleDisconnected true
                    :fit false
                    :animate true
                    :avoidOverlap true}
       :style word-arc-style
       :elements (word-arc-elements sentences)
       :ready word-arc-ready!}))))
