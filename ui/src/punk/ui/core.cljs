(ns punk.ui.core
  (:require [hx.react :as hx :refer [defnc]]
            [hx.hooks :refer [<-deref <-state <-effect <-ref]]
            ["react-dom" :as react-dom]
            ["react-grid-layout" :as GridLayout]
            [goog.object :as gobj]
            [clojure.string :as s]
            [clojure.core.async :as a]
            [cljs.tools.reader.edn :as edn]
            [frame.core :as f]
            [cljs.tagged-literals]
            [punk.ui.views :as views]
            [punk.ui.components :as pc]))

;;
;; Helpers
;;

;; Window-size hook based on https://github.com/rehooks/window-size/blob/master/index.js

(defn get-size [window]
  {:inner-height (.-innerHeight window)
   :inner-width (.-innerWidth window)
   :outer-height (.-outerHeight window)
   :outer-width (.-outerWidth window)})

(defn <-window-size
  ([] (<-window-size js/window))
  ([window]
   (let [window-size (<-state (when window (get-size window)))
         handle-resize #(reset! window-size (get-size window))]
     ;; Effect adds the event handler to the window resize event
     (<-effect
      (fn []
        (when window
          (.addEventListener window "resize" handle-resize)
          ;; return the unsubscribe function
          #(.removeEventListener window "resize" handle-resize)))
      ;; only re-sub on re-mount or new window val
      [window])

     ;; return value
     @window-size)))

(defn <-mouse-move [cb]
  (<-effect (fn []
              (.addEventListener js/window "mousemove" cb)
              #(.removeEventListener js/window "mousemove" cb))
            [cb]))

(defn <-mouse-up [cb]
  (<-effect (fn []
              (.addEventListener js/window "mouseup" cb)
              #(.removeEventListener js/window "mouseup" cb))
            [cb]))

(defn <-new-window [{:keys [ui/css]}]
  (let [win&container (<-state nil)]
    (<-effect
     (fn []
       (let [ext-window (.open js/window "" "" "width=800,height=800,left=200,top=200")
             container-el (-> ext-window .-document (.createElement "div"))]
         (when css
           (doseq [sheet css]
             (let [link-tag (. js/document createElement "link")]
               (. link-tag setAttribute "rel" "stylesheet")
               (. link-tag setAttribute "type" "text/css")
               (. link-tag setAttribute "href" sheet)
               (-> ext-window .-document .-body (.appendChild link-tag)))))
         (-> ext-window .-document .-body (.appendChild container-el))
         (reset! win&container [ext-window container-el])
         #(.close ext-window)))
     [])
    win&container))

;;
;; UI state
;;

(defonce ui-db (atom {:entries []
                      :history []
                      :current nil
                      :current/loading false
                      :collapsed? true
                      :drawer-width 50
                      :grid-layout #js [#js {:i "metadata" :x 0 :y 0 :w 12 :h 6}
                                        #js {:i "current" :x 0 :y 6 :w 12 :h 6}
                                        #js {:i "entries" :x 0 :y 12 :w 12 :h 6}]
                      :views [{:id :punk.view/nil
                               :match nil?
                               :view nil}

                              {:id :punk.view/map
                               :match map?
                               :view #'views/MapView}

                              {:id :punk.view/set
                               :match set?
                               :view #'views/SetView}

                              {:id :punk.view/coll
                               :match (every-pred
                                       coll?
                                       (comp not map?))
                               :view #'views/CollView}

                              {:id :punk.view/edn
                               :match any?
                               :view #'views/EdnView}]
                      :next.view/key nil
                      :current.view/selected nil}))

(defonce ui-frame (f/create-frame
                   (f/inject-cofx :db)))

(defonce dispatch #(f/dispatch ui-frame %))

(f/reg-cofx
 ui-frame :db
 (fn db-cofx [cofx]
   (assoc cofx :db @ui-db)))

(f/reg-fx
 ui-frame :db
 (fn db-fx [v]
   (when (not (identical? @ui-db v))
     (reset! ui-db v))))

(defn dbg [f]
  (fn [x]
    (f x)
    x))

(def debug-db
  (frame.interceptors/->interceptor
   :id :punk/debug-db
   :before (dbg (fn [x] (js/console.log "db/before> " (-> x :coeffects :db))))
   :after (dbg (fn [x] (js/console.log "db/after> " (-> x :effects :db))))))

(def debug-event
  (frame.interceptors/->interceptor
   :id :punk/debug-event
   :before (dbg (fn [x] (js/console.log "event> " (-> x :coeffects :event))))))

;;
;; UI Events
;;

(f/reg-event-fx
 ui-frame :punk.ui.drawer/toggle
 []
 (fn [{:keys [db]} _]
   {:db (update db :collapsed? not)}))

(f/reg-event-fx
 ui-frame :punk.ui.browser/view-entry
 [#_debug-db #_debug-event]
 (fn [{:keys [db]} [_ x]]
   {:db (assoc db
               :current x
               :current/loading false
               :current.view/selected nil
               :history [])}))

(f/reg-event-fx
 ui-frame :punk.ui.browser/history-back
 [#_debug-db #_debug-event]
 (fn [{:keys [db]} _]
   {:db (-> db
            (update :history pop)
            (assoc :current (-> db :history peek)
                   :current.view/selected nil))}))

(f/reg-event-fx
 ui-frame :punk.ui.browser/history-nth
 [#_debug-db #_debug-event]
 (fn [{:keys [db]} [_ idx]]
   (let [current (nth (:history db) idx)]
   {:db (-> db
            (assoc :history (vec (take idx (:history db)))
                   :current current
                   :next.view/key (:nav-key current)
                   :current.view/selected nil))})))

(f/reg-event-fx
 ui-frame :punk.ui.browser/nav-to-next
 [#_debug-db #_debug-event]
 (fn [{:keys [db]} [_ idx key v]]
   (let [next-value (get (:current db) key v)] ; XXX: ignore v?
     {:db (-> db
              (assoc :current/loading true
                     :next.view/key key))
      :emit [:nav idx key next-value]})))

(f/reg-event-fx
 ui-frame :punk.ui.browser/select-current-view
 []
 (fn [{:keys [db]} [_ id]]
   {:db (assoc db :current.view/selected id)}))

(f/reg-event-fx
 ui-frame :punk.ui.browser/register-view
 [#_debug-db]
 (fn [{:keys [db]} [_ v]]
   {:db (update db :views conj v)}))

(f/reg-event-fx
 ui-frame :punk.ui.browser/unregister-view
 []
 (fn [{:keys [db]} [_ id]]
   ;; filterv here is important to preserve order
   (let [views' (filterv #(not= id (:id %)) (:views db))]
     {:db (assoc db :views views')})))

(f/reg-event-fx
 ui-frame :punk.ui.browser/change-layout []
 (fn [{:keys [db]} [_ layout]]
   {:db (assoc db :grid-layout layout)}))

(f/reg-event-fx
 ui-frame :punk.ui.drawer/change-width []
 (fn [{:keys [db]} [_ width]]
   {:db (assoc db :drawer-width width)}))

(defn register-view!
  [& {:keys [id match view] :as v}]
  (dispatch [:punk.ui.browser/register-view v]))

(defn unregister-view!
  [id]
  (dispatch [:punk.ui.browser/unregister-view id]))

(defn match-views [views data]
  (filter #((:match %) data) views))

;;
;; Punk events
;;

(f/reg-event-fx
 ui-frame :entry
 [#_debug-db #_debug-event]
 (fn [cofx [_ idx x]]
   {:db (update (:db cofx) :entries conj (assoc x :idx idx))}))

(f/reg-event-fx
 ui-frame :nav
 [#_debug-event #_debug-db]
 (fn [{:keys [db]} [_ idx x]]
   {:db (-> db
            (assoc
             :current/loading false
             :current x
             :current.view/selected nil
             :next.view/key nil)
            (update :history
                    conj (assoc (:current db)
                                :nav-key (:next.view/key db))))}))

;;
;; Browser panes
;;

(defnc Breadcrumbs [{:keys [items on-click]}]
  [:<>
   (map-indexed
    (fn [i x]
      [:a {:href "#"
           :on-click #(do (.preventDefault %)
                          (on-click (+ i 1)))
           :class "punk__breadcrumb"} (str x)])
    (drop-last items))
   (when (seq items)
     [:span {:class ["punk__breadcrumb" "punk__breadcrumb_last"]} (str (last items))])])

(defnc Metadata [{:keys [data]}]
  [pc/Pane
   {:title "Metadata"
    :id "punk__metadata"}
   [views/MapView
    {:data data}]])

(defnc Current [{:keys [history view views current]}]
  [pc/Pane
   {:title "Current"
    :id "punk__current"
    :controls [:div
               [:select {:value (str (:id view))
                         :on-change #(dispatch [:punk.ui.browser/select-current-view
                                                (keyword (subs (.. % -target -value) 1))])}
                (for [vid (map (comp str :id) views)]
                  [:option {:key vid} vid])]
               [:button {:type "button"
                         :id "punk__current__back-button"
                         :disabled (empty? history)
                         :on-click #(dispatch [:punk.ui.browser/history-back])} "<"]
               [Breadcrumbs
                {:items (map :nav-key history)
                 :on-click #(dispatch [:punk.ui.browser/history-nth %])}]]}
   [(:view view)
    {:data (-> current :value)
     :nav #(dispatch [:punk.ui.browser/nav-to-next
                      (-> current :idx) %2 %3])}]])

(defnc Browser [{:keys [state width]}]
  (let [metadata (or (-> state :current :meta) {})
        current-views (-> (:views state)
                          (match-views (-> state :current :value)))

        current-view (if (:current.view/selected state)
                       (first (filter #(= (:id %) (:current.view/selected state)) current-views))
                       (first current-views))
        update-layout #(dispatch [:punk.ui.browser/change-layout %])]
    [:div {:id "punk__container"}
     [GridLayout
      {:layout (:grid-layout state)
       :onLayoutChange update-layout
       :cols 12
       :rowHeight 30
       :width width
       :draggableHandle ".punk__pane__titlebar"}
      ;; Metadata
      [:div {:key "metadata"}
       [Metadata {:data metadata}]]
      ;; Current
      [:div {:key "current"}
       [Current {:history (:history state)
                 :view current-view
                 :views current-views
                 :current (-> state :current)}]]
      ;; Entries
      [:div {:key "entries"}
       [pc/Pane {:title "Entries" :id "punk__entries"}
        (let [entries (reverse (map-indexed vector (:entries state)))]
          [pc/Table {:cols [[:id first [:div {:class "punk__entry-column__id"}]]
                            [:value (comp :value second) [:div {:class "punk__entry-column__value"}]]
                            ;; [:meta (comp :meta second) {:flex 5}]
                            ]
                     :on-entry-click (fn [_ entry]
                                       (dispatch [:punk.ui.browser/view-entry (second entry)]))
                     :data entries}])]]]]))

(def dragging? (atom false))

(defnc Popup [{:keys [on-close opts]}]
  (let [[win target] @(<-new-window opts)
        state (<-deref ui-db)
        win-size (<-window-size win)]
    (<-effect (fn []
                (when win
                  (.addEventListener win "unload"
                                     on-close)
                  #(.removeEventListener win "unload" on-close)))
              [win])
    (when target
      (react-dom/createPortal
       (hx/f [Browser {:state state
                       :width (- (:inner-width win-size) 15)}])
       target))))

(defnc DrawerRender [{:keys [on-pop-out]}]
  (let [state (<-deref ui-db)
        collapsed? (:collapsed? state)
        win-size (<-window-size)
        move-handler #(when @dragging?
                        (dispatch
                         [:punk.ui.drawer/change-width
                          (* 100
                             (/ (- (:inner-width win-size) (.. % -clientX))
                                (:inner-width win-size)))]))
        width (if collapsed? "20px" (/ (:inner-width win-size)
                                       (/ 100 (:drawer-width state))))]
    (<-mouse-move move-handler)
    (<-mouse-up #(reset! dragging? false))
    [:div {:id "punk__drawer-container"
           :style {:width width}}
     [:div {:id "punk__drawer-container__inner"}
      (when-not collapsed?
        [:div {:id "punk__drawer-dragger"
               :on-mouse-down #(do
                                 (.preventDefault %)
                                 (reset! dragging? true))}])
      [:div {:id "punk__drawer-toggle"
             :on-click #(dispatch [:punk.ui.drawer/toggle])}
       [:div {:id "punk__drawer-toggle__top-arrow"}
        (if collapsed? ">>" "<<")]
       [:div {:id "punk__drawer-toggle__bottom-arrow"}
        (if collapsed? ">>" "<<")]]
      (when (not collapsed?)
        [:div {:id "punk__drawer__browser"}
         [Browser {:state state
                   :width (- width 25)
                   :measureBeforeMount true}]
         [:div {:id "punk__pop-out-button"
                :on-click on-pop-out} "Pop out"]])]]))

(defnc Drawer [{:keys [opts]}]
  (let [pop-out? (<-state false)]
    (if @pop-out?
      [Popup {:on-close #(do (reset! pop-out? false))
              :opts opts}]
      [DrawerRender {:on-pop-out #(reset! pop-out? true)}])))

(defnc JustBrowser [_]
  (let [state (<-deref ui-db)
        win-size (<-window-size)]
    [Browser {:state state :width (:inner-width win-size)}]))

(defn external-handler [ev]
  (try
    (dispatch (edn/read-string
               {:readers {;; 'js (with-meta identity {:punk/literal-tag 'js})
                          'inst cljs.tagged-literals/read-inst
                          'uuid cljs.tagged-literals/read-uuid
                          'queue cljs.tagged-literals/read-queue}
                :default tagged-literal}
               ev))
    (catch js/Error e
      (println e))))

(defn drawer-toggler []
  (dispatch [:punk.ui.drawer/toggle]))

(defn ^:export start! [node input output opts]
  {:pre [(not (nil? input))
         (not (nil? output))]}
  (.addEventListener
   js/document "keydown"
   (fn [ev]
     (when (and (.-ctrlKey ev) (.-altKey ev) (= "KeyP" (.-code ev)))
       (drawer-toggler))))
  (.unsubscribe ^js input
                #'external-handler)
  (.subscribe ^js input
              #'external-handler)
  (f/reg-fx
   ui-frame :emit
   (fn [v]
     (.put ^js output (pr-str v))))
  (let [opts (edn/read-string opts)
        drawer? (get opts :drawer? true)]
    (react-dom/render (hx/f (if drawer?
                              [Drawer {:opts opts}]
                              [JustBrowser {:opts opts}])) node)))

#_(console.log @ui-db)
