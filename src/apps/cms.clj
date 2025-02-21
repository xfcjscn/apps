(ns apps.cms
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [ring.util.response :as response]
            [prone.debug :refer [debug]]
            [cprop.source :as cps]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [hiccup.core :refer [html]]
            [hiccup.page :refer :all]
            [hiccup.element :refer :all]
            [hiccup.form :refer :all]
            [clj-org.org :refer [parse-org]]
            [datomic.api :as d]
            [apps.ring-mw :as mw]

            [endophile.core :refer [mp]]
            [endophile.hiccup :refer [to-hiccup]]

            [oauth.v2 :as oauth2]
            [com.stuartsierra.component :as component]
            [system.repl :refer [system set-init! start stop reset]]
            [system.components.jetty :refer [new-web-server]]
            [system.components.datomic :refer [new-datomic-db]]
            [cemerick.pomegranate :refer [add-dependencies]]))

;; views
(defn comments []
  [:section
   [:div {:class "ui rating" :data-max-rating "5"}]
   [:div {:class "ui labeled button"}
    [:div {:class "ui basic blue button"}
     [:i {:class "heart icon"}]
     "Like"]
    [:a {:class "ui basic red left pointing label"}
     "1,048"]]
   [:div {:class "ui comments"}
    [:div {:class "comment"}
     [:a {:class "avatar"}]
     [:div {:class "content"}
      [:a {:class "author"} "Steve Jobes"]]
     [:div {:class "metadata"}
      [:div {:class "date"} "2 days ago"]]
     [:div {:class "text"} "Revolutionary!"]
     [:div {:class "actions"}
      [:a {:class "reply active"} "reply"]]
     [:form {:class "ui reply form" :action "/comment" :method "post"}
      [:div {:class "field"}
       [:textarea {:name "content"}]]
      [:div {:class "ui primary submit labeled icon button"}
       [:i {:class "icon edit"}] " Add Reply"]]]]])

(defn default-layout [content request]
  [:html
   [:head
    [:meta {:property "qc:admins" :content "1541247527630123056375"}]
    (include-css "/assets/semantic/dist/semantic.css")]
   [:body
    (if (:identity request)
      [:div
       (str "Welcome: " (-> request :session :qq-user-info :nickname))
       #_(image (-> request :session :qq-user-info :figureurl))
       #_(image (-> request :session :qq-user-info :figureurl-2))
       (image (-> request :session :qq-user-info :figureurl-qq-1))
       #_(image (-> request :session :qq-user-info :figureurl-qq-2))
       (link-to "/remove-identity" "logout")]
      (link-to "/oauth2/entrypoint"
               (image "http://qzonestyle.gtimg.cn/qzone/vas/opensns/res/img/bt_white_76X24.png")))

    content
    (comments)
    (include-js "/assets/jquery/jquery.js" "/assets/semantic/dist/semantic.js" "http://tajs.qq.com/stats?sId=56953083"
                "/cms.js")]])

(defn link-list [files request]
  [:ol
   (map (fn [file]
          (let [file-name (.getName file)
                file-name-view (str file-name (when (.isDirectory file) "/"))]
            [:li (link-to (str (-> request :uri) "/" file-name) file-name-view)]))
        files)])

;; data
(def schema
  [{:db/id #db/id[:db.part/db]
    :db/ident :cms/comment
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/fulltext true
    :db/doc "Comments for article"
    :db.install/_attribute :db.part/db}])



;; logic

(def env (-> {:qq-oauth2 {:authorization-uri "https://graph.qq.com/oauth2.0/authorize"
                          ;; if test in local, need to do host mapping, as localhost callback is forbidden by qq
                          :redirect-uri "http://www.sharkxu.com/oauth2/callback"
                          :client-id "101326937"
                          :client-secret "b9892d627e6ff3985eb25b32c1ade573"
                          :options {:scope ["get_user_info"]
                                    :response-type "code"}
                          :access-token-uri "https://graph.qq.com/oauth2.0/token"

                          :qq-openid-uri "https://graph.qq.com/oauth2.0/me"
                          :qq-user-info-uri "https://graph.qq.com/user/get_user_info"}}
             (merge (:cms (cps/from-system-props)))))


(def qq-oauth2 (env :qq-oauth2))

(def handler
  (routes
   (GET "/" request (-> "/Users/fuchengxu/Projects/apps/my-cognition.org"
                        io/file
                        slurp
                        parse-org
                        :content
                        (default-layout request)
                        html))
   (GET "/cms/md*" request (let [file (-> (:uri request) (subs 1) io/resource .getFile io/file)]
                             (if (.isDirectory file)
                               (-> file .listFiles (link-list request) (default-layout request) html)
                               (-> file slurp mp to-hiccup (default-layout request) html))))
   (GET "/remove-identity" {session :session}
        (as-> session $
          (dissoc $ :identity)
          (assoc (response/redirect "/") :session $)))
   (GET "/oauth2/entrypoint" []
        (as-> qq-oauth2 $
          (into ((juxt :authorization-uri :client-id :redirect-uri) $) (->> $ :options (apply concat)))
          (apply oauth2/oauth-authorization-url $)
          (response/redirect $)))
   (GET "/oauth2/callback" {{code :code} :params session :session}
        #_(debug)
        (as-> qq-oauth2 $
          (conj ((juxt :access-token-uri :client-id :client-secret) $) code (:redirect-uri $))
          (apply oauth2/oauth-access-token $)
          (:access-token $)
          (oauth2/oauth-client $)
          (assoc session :oauth-client $)
          (assoc $ :identity (as-> $ $$
                               (:oauth-client $$)
                               ($$ {:method :get
                                    :url (:qq-openid-uri qq-oauth2)})
                               (str $$)
                               (re-find #"(?<=\"openid\":\").*(?=\")" $$)
                               (clojure.string/replace $$ "-" "")))
          (assoc $ :qq-user-info (as-> $ $$
                                   (:oauth-client $$)
                                   ($$ {:method :get
                                        :url (:qq-user-info-uri qq-oauth2)
                                        :query-params {"oauth_consumer_key" (:client-id qq-oauth2)
                                                       "openid" (:identity $)}
                                        :as :json})))
          (assoc (response/redirect (-> $ :history rseq (nth 1))) :session $)))
   (POST "/comment" [content] (as-> (log/spy content) $
                                [:db/add (d/tempid :db.part/user) :cms/comment $]
                                [$]
                                (d/transact (-> system :datomic-db :conn) $)
                                (str "Done with result: " $)))
   (GET "/comment" [id] (-> (d/q '[:find ?c :where [?e :cms/comment ?c]] (-> system :datomic-db :conn d/db))
                            str))
   (route/not-found "Page Not Found")))


;; this must be no parameter so it can be invoked by system/init
(defn system-cms []
  (as-> handler $
    (mw/wrap-defaults $)
    (component/system-map
     ;; in linux port below 1024 can only be opened by root
     :web (new-web-server 8080 $)
     ;;run: bin\transactor config\samples\dev-transactor-template.properties to start transactor
     :datomic-db (new-datomic-db "datomic:mem://localhost:4334/cms"))))

(defn init-schema []
  (d/transact (-> system :datomic-db :conn) schema))

(defn -main
  "Start the application"
  []
  (set-init! #'system-cms)
  (start)
  (init-schema))

#_(-main)

#_(stop)
#_(reset)

#_(add-dependencies :coordinates '[[xxxxxxx "1.2.3"]]
                    :repositories (merge cemerick.pomegranate.aether/maven-central
                                         {"clojars" "http://clojars.org/repo"}))

