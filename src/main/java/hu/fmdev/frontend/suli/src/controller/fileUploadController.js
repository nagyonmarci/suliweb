app.controller('FileUploadController', ['$scope', '$http', function($scope, $http) {

    $scope.uploadFile = function(file, password, zipName) {
        var formData = new FormData();
        formData.append('file', file);
        formData.append('password', password);
        formData.append('zipName', zipName);

        $http.post('/api/files/upload', formData, {
            transformRequest: angular.identity,
            headers: { 'Content-Type': undefined }
        }).then(function(response) {
            alert('Fájl sikeresen feltöltve és tömörítve: ' + response.data);
        }, function(error) {
            alert('Hiba történt: ' + error.data);
        });
    };

}]);
