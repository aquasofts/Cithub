# Retrofit reads service/parameter annotations and suspend Continuation<T> signatures at runtime.
# R8 full mode may otherwise rewrite T to an optimized abstract type that Gson cannot instantiate.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepclassmembers class edu.ccit.webvpn.feature.tieba.network.** {
    <fields>;
}
-keep interface edu.ccit.webvpn.feature.tieba.network.TiebaSupportApi { *; }
-keep interface edu.ccit.webvpn.feature.tieba.network.TiebaReadApi { *; }
-keep interface edu.ccit.webvpn.feature.tieba.network.TiebaPicPageApi { *; }
-keep interface edu.ccit.webvpn.feature.tieba.network.TiebaOfficialApi { *; }
-keep interface edu.ccit.webvpn.feature.tieba.network.TiebaWebSignApi { *; }

# Gson constructs these official JSON response types reflectively. Keep the concrete classes and
# their nested response objects intact; keeping fields alone does not prevent R8 class optimization.
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaLoginBean { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaLoginBean$* { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaInitNickNameBean { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaInitNickNameBean$* { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaSignResultBean { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaSignResultBean$* { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaSyncBean { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaSyncBean$* { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaCommonResponse { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaWebSignBean { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.TiebaWebSignBean$* { *; }

# The Sofire handshake serializes its request and reflectively instantiates both encrypted-response
# envelopes. R8 may otherwise merge the tiny private data classes and leave Gson with an abstract
# optimized type at runtime.
-keep class edu.ccit.webvpn.feature.tieba.network.SofireRequestBody { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.SofireResponse { *; }
-keep class edu.ccit.webvpn.feature.tieba.network.SofireResponseData { *; }
-keep class com.huanchengfly.tieba.post.api.models.protos.** { *; }
