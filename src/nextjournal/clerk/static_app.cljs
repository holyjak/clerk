(ns nextjournal.clerk.static-app
  (:require ["react-dom/client" :as react-client]
            [clojure.set :as set]
            [clojure.string :as str]
            [nextjournal.clerk.render :as render]
            [nextjournal.clerk.render.localstorage :as localstorage]
            [nextjournal.clerk.sci-env :as sci-env]
            [reagent.core :as r]
            [reagent.dom.server :as dom-server]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]
            [sci.core :as sci]))

(defn doc-url [{:keys [path->url current-path bundle?]} path]
  (let [url (path->url path)]
    (if bundle?
      (str "#/" url)
      (let [url (cond-> url
                  (and (exists? js/document)
                       (= (.. js/document -location -protocol) "file:")
                       (or (nil? url)
                           (str/ends-with? url "/")))
                  (str "index.html"))
            dir-depth (get (frequencies current-path) \/ 0)
            relative-root (str/join (repeat dir-depth "../"))]
        (str relative-root url)))))

(defn hiccup [hiccup]
  {:nextjournal/viewer render/html-viewer
   :nextjournal/value hiccup})

(defn show [{:as view-data :git/keys [sha url] :keys [bundle? doc path url->path]}]
  (let [header [:div.viewer.w-full.max-w-prose.px-8
                [:div.mb-8.text-xs.sans-serif.text-gray-400.not-prose
                 (when (not= "" path)
                   [:<>
                    [:a.hover:text-indigo-500.dark:hover:text-white.font-medium.border-b.border-dotted.border-gray-300
                     {:href (doc-url view-data "")} "Back to index"]
                    [:span.mx-1 "/"]])
                 [:span
                  "Generated with "
                  [:a.hover:text-indigo-500.dark:hover:text-white.font-medium.border-b.border-dotted.border-gray-300
                   {:href "https://github.com/nextjournal/clerk"} "Clerk"]
                  (when (and url sha (contains? url->path path))
                    [:<>
                     " from "
                     [:a.hover:text-indigo-500.dark:hover:text-white.font-medium.border-b.border-dotted.border-gray-300
                      {:href (str url "/blob/" sha "/" (url->path path))} (url->path path) "@" [:span.tabular-nums (subs sha 0 7)]]])]]]]
    (render/set-state! {:doc (cond-> (assoc doc :bundle? bundle?)
                               (vector? (get-in doc [:nextjournal/value :blocks]))
                               (update-in [:nextjournal/value :blocks] (partial into [(hiccup header)])))})
    [render/root]))

(defn index [{:as view-data :keys [paths]}]
  (when (exists? js/document)
    (set! (.-title js/document) "Clerk"))
  (r/with-let [!state (r/atom {:dark-mode? (localstorage/get-item render/local-storage-dark-mode-key)})
               ref-fn #(when % (render/setup-dark-mode! !state))]
    [:div.bg-gray-100.dark:bg-gray-900.flex.justify-center.overflow-y-auto.w-screen.h-screen.p-4.md:p-0
     {:ref ref-fn}
     [:div.fixed.top-2.left-2.md:left-auto.md:right-2.z-10
      [render/dark-mode-toggle !state]]
     [:div.md:my-12.w-full.md:max-w-lg
      [:div.bg-white.dark:bg-gray-800.shadow-lg.rounded-lg.border.dark:border-gray-800.dark:text-white
       [:div.px-4.md:px-8.py-3
        [:h1.text-xl "Clerk"]]
       (into [:ul]
             (map (fn [path]
                    [:li.border-t.dark:border-gray-900
                     [:a.pl-4.md:pl-8.pr-4.py-2.flex.w-full.items-center.justify-between.hover:bg-indigo-50.dark:hover:bg-gray-700
                      {:href (doc-url view-data path)}
                      [:span.text-sm.md:text-md.monospace.flex-auto.block.truncate path]
                      [:svg.h-4.w-4.flex-shrink-0 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                       [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M9 5l7 7-7 7"}]]]]))
             (sort paths))]
      [:div.my-4.md:mb-0.text-xs.text-gray-400.sans-serif.px-4.md:px-8
       [:a.hover:text-indigo-600.dark:hover:text-white
        {:href "https://github.com/nextjournal/clerk"}
        "Generated with Clerk."]]]]))



(defn get-routes [docs]
  (let [index? (contains? docs "")]
    [["/*path" {:name ::show :view show}]
     ["/" {:name ::index :view (if index? show index)}]]))


(defonce !match (r/atom nil))
(defonce !state (r/atom {}))

(defn root []
  (let [{:keys [data path-params] :as match} @!match
        {:keys [view]} data
        view-data (merge @!state data path-params {:doc (get-in @!state [:path->doc (:path path-params "")])})]
    [:div.flex.min-h-screen.bg-white.dark:bg-gray-900
     [:div.flex-auto.w-screen.scroll-container
      (if view
        [view view-data]
        [:pre (pr-str match)])]]))

(defonce container
  (and (exists? js/document) (js/document.getElementById "clerk-static-app")))

(defonce hydrate?
  (when container
    (pos? (.-childElementCount container))))

(defonce react-root
  (when container
    (if hydrate?
      (react-client/hydrateRoot container (r/as-element [root]))
      (react-client/createRoot container))))

(defn ^:dev/after-load mount []
  (when (and react-root (not hydrate?))
    (.render react-root (r/as-element [root]))))

;; next up
;; - jit compiling css
;; - support viewing source clojure/markdown file (opt-in)

(defn ^:export init [{:as state :keys [bundle? path->doc path->url current-path]}]
  (let [url->doc (set/rename-keys path->doc path->url)]
    (reset! !state (assoc state
                          :path->doc url->doc
                          :url->path (set/map-invert path->url)))
    (sci/alter-var-root sci-env/doc-url (constantly (partial doc-url @!state)))
    (if bundle?
      (let [router (rf/router (get-routes url->doc))]
        (rfe/start! router #(reset! !match %1) {:use-fragment true}))
      (reset! !match {:data {:view (if (str/blank? current-path) index show)} :path-params {:path (path->url current-path)}}))
    (mount)))

(defn ^:export ssr [state-str]
  (init (sci-env/read-string state-str))
  (dom-server/render-to-string [root]))
