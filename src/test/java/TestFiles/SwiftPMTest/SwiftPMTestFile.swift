import Alamofire

AF.request("https://www.example.com").response { response in
    print(response)
}